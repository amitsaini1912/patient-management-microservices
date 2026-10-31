#!/usr/bin/env python3
"""Day 22: what the SERVERS did during a k6 run - the part k6 can't see (where the time went).

Reads each service's Prometheus endpoint (and CPU time of the Java pids from load-test/run/) before and after a run
and prints the difference: server-side time per endpoint, DB pool waits, risk check time + fallbacks, CPU per process.

    python3 load-test/server_stats.py snapshot load-test/run/before.json
    k6 run ...
    python3 load-test/server_stats.py diff load-test/run/before.json      # prints, and saves nothing
Stdlib only.
"""
from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.request

SERVICES = {
    "gateway": "http://localhost:4004/actuator/prometheus",
    "auth": "http://localhost:4005/actuator/prometheus",
    "billing": "http://localhost:4001/actuator/prometheus",
    "card-vault": "http://localhost:4020/actuator/prometheus",
    "simulator": "http://localhost:4010/actuator/prometheus",
    "risk-engine": "http://localhost:8000/metrics/prometheus",
}
RUN_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run")
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+(\S+)')
KEEP = re.compile(r"^(http_server_requests_seconds_(count|sum)|hikaricp_connections_(acquire_seconds_(count|sum)|"
                  r"pending|active|max|timeout_total)|billing_risk_decisions_total|billing_risk_duration_seconds_(count|sum)|"
                  r"billing_payment_events_total|billing_outbox_pending|risk_request_duration_seconds_(count|sum)|"
                  r"risk_budget_exceeded_total|risk_unavailable_total|risk_decisions_total|process_cpu_seconds_total|"
                  r"jvm_threads_live_threads|executor_active_threads|tomcat_threads_busy_threads)$")


def scrape(url: str) -> dict[str, float]:
    out: dict[str, float] = {}
    try:
        text = urllib.request.urlopen(url, timeout=5).read().decode()
    except Exception as e:  # noqa: BLE001 - a service that is down is reported, not fatal
        print(f"!! {url}: {e}", file=sys.stderr)
        return out
    for line in text.splitlines():
        m = LINE.match(line)
        if m and KEEP.match(m.group(1)):
            try:
                out[m.group(1) + (m.group(2) or "")] = float(m.group(3))
            except ValueError:
                pass
    return out


def java_cpu_seconds() -> dict[str, float]:
    tick = os.sysconf("SC_CLK_TCK")
    out = {}
    for f in sorted(os.listdir(RUN_DIR)) if os.path.isdir(RUN_DIR) else []:
        if f.endswith(".pid"):
            pid = open(os.path.join(RUN_DIR, f)).read().strip()
            try:
                fields = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
                out[f[:-4]] = (int(fields[11]) + int(fields[12])) / tick   # utime + stime
            except OSError:
                pass
    return out


def snapshot() -> dict:
    return {"time": time.time(), "metrics": {s: scrape(u) for s, u in SERVICES.items()}, "cpu": java_cpu_seconds()}


def label(key: str, name: str) -> str | None:
    m = re.search(name + r'="([^"]*)"', key)
    return m.group(1) if m else None


def diff(before: dict, after: dict) -> None:
    secs = after["time"] - before["time"]
    print(f"window: {secs:.0f} s\n")

    def d(svc: str, key: str) -> float:
        return after["metrics"].get(svc, {}).get(key, 0.0) - before["metrics"].get(svc, {}).get(key, 0.0)

    print("== server-side time per endpoint (mean over the window; k6 shows the client side + percentiles)")
    for svc in ("gateway", "auth", "billing", "card-vault", "simulator"):
        for key in sorted(after["metrics"].get(svc, {})):
            if not key.startswith("http_server_requests_seconds_count"):
                continue
            n = d(svc, key)
            if n < 1:
                continue
            total = d(svc, key.replace("_count", "_sum", 1))
            print(f"  {svc:11} {label(key, 'method') or '':5} {label(key, 'uri') or '':40} "
                  f"status={label(key, 'status')} n={n:7.0f} ({n / secs:6.1f}/s) mean={1000 * total / n:7.1f} ms")
    for key in after["metrics"].get("risk-engine", {}):
        if key.startswith("risk_request_duration_seconds_count"):
            n = d("risk-engine", key)
            if n >= 1:
                total = d("risk-engine", key.replace("_count", "_sum", 1))
                print(f"  risk-engine {label(key, 'endpoint') or '':46} status={label(key, 'status')} n={n:7.0f} "
                      f"({n / secs:6.1f}/s) mean={1000 * total / n:7.1f} ms")

    print("\n== DB connection pools (time waiting for a connection = pool too small or connections held too long)")
    for svc in ("billing", "card-vault", "auth"):
        m = after["metrics"].get(svc, {})
        for key in m:
            if key.startswith("hikaricp_connections_acquire_seconds_count"):
                n = d(svc, key)
                total = d(svc, key.replace("_count", "_sum", 1))
                pool = label(key, "pool")
                mx = next((v for k, v in m.items() if k.startswith("hikaricp_connections_max") and pool in k), 0)
                to = next((d(svc, k) for k in m if k.startswith("hikaricp_connections_timeout_total") and pool in k), 0)
                if n:
                    print(f"  {svc:11} pool={pool} max={mx:.0f} acquires={n:.0f} mean wait={1000 * total / n:.2f} ms "
                          f"timeouts={to:.0f}")

    print("\n== risk check as billing saw it (source ENGINE = engine answered, FALLBACK = billing's own rules)")
    for key in sorted(after["metrics"].get("billing", {})):
        if key.startswith("billing_risk_decisions_total"):
            n = d("billing", key)
            if n:
                print(f"  {key.split('{', 1)[1].rstrip('}'):90} {n:7.0f}")
        if key.startswith("billing_risk_duration_seconds_count"):
            n = d("billing", key)
            if n:
                total = d("billing", key.replace("_count", "_sum", 1))
                print(f"  risk check duration source={label(key, 'source')}: n={n:.0f} mean={1000 * total / n:.1f} ms")
    for name in ("risk_budget_exceeded_total", "risk_unavailable_total"):
        for key in after["metrics"].get("risk-engine", {}):
            if key.startswith(name) and d("risk-engine", key):
                print(f"  engine {key}: {d('risk-engine', key):.0f}")

    print("\n== payment events counted by billing (after commit)")
    for key in sorted(after["metrics"].get("billing", {})):
        if key.startswith("billing_payment_events_total") and d("billing", key):
            print(f"  {key.split('{', 1)[1].rstrip('}'):70} {d('billing', key):7.0f}")
    print(f"  outbox pending now: {after['metrics'].get('billing', {}).get(next((k for k in after['metrics'].get('billing', {}) if k.startswith('billing_outbox_pending')), ''), 0):.0f}")

    print("\n== CPU used in the window (1.00 = one core busy the whole time)")
    for svc, cpu in after["cpu"].items():
        print(f"  {svc:11} {(cpu - before['cpu'].get(svc, cpu)) / secs:5.2f} cores")
    rk = next((k for k in after["metrics"].get("risk-engine", {}) if k.startswith("process_cpu_seconds_total")), None)
    if rk:
        print(f"  {'risk-engine':11} {d('risk-engine', rk) / secs:5.2f} cores   (one uvicorn worker = at most ~1.00)")
    print("  (Postgres / Kafka / Redis containers: see `docker stats` during the run)")


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] not in ("snapshot", "diff"):
        print(__doc__)
        return 2
    if sys.argv[1] == "snapshot":
        os.makedirs(os.path.dirname(os.path.abspath(sys.argv[2])), exist_ok=True)
        json.dump(snapshot(), open(sys.argv[2], "w"))
        print(f"saved {sys.argv[2]}")
    else:
        diff(json.load(open(sys.argv[2])), snapshot())
    return 0


if __name__ == "__main__":
    sys.exit(main())
