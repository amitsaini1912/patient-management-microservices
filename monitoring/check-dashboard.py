"""
Runs every query of the committed Grafana dashboards against Prometheus (Day 20b, ADR 0020), so a renamed metric
or a typo in a panel shows up here instead of as an empty panel nobody notices.

  python3 monitoring/check-dashboard.py                 # Prometheus at http://localhost:9090
  python3 monitoring/check-dashboard.py --prometheus http://host:9090

Per query: OK (series returned), EMPTY (valid but no data - e.g. no traffic of that kind yet) or ERROR (Prometheus
refused it). Exit 1 on any ERROR, or on EMPTY with --strict.
"""

import argparse
import glob
import json
import os
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def query(base: str, expr: str) -> tuple[str, str]:
    expr = expr.replace("$__rate_interval", "1m")
    url = f"{base}/api/v1/query?{urllib.parse.urlencode({'query': expr})}"
    try:
        body = json.load(urllib.request.urlopen(url, timeout=10))
    except urllib.error.HTTPError as e:
        return "ERROR", json.load(e).get("error", str(e))
    result = body["data"]["result"]
    values = [r["value"][1] for r in result]
    return ("OK" if result else "EMPTY"), ", ".join(values[:4]) + (" ..." if len(values) > 4 else "")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--prometheus", default="http://localhost:9090")
    p.add_argument("--strict", action="store_true", help="EMPTY counts as a failure too")
    args = p.parse_args()
    failed = 0
    for path in sorted(glob.glob(os.path.join(HERE, "grafana", "dashboards", "*.json"))):
        dashboard = json.load(open(path))
        print(f"== {dashboard['title']} ({os.path.basename(path)})")
        for panel in dashboard["panels"]:
            for target in panel.get("targets", []):
                status, detail = query(args.prometheus, target["expr"])
                failed += status == "ERROR" or (args.strict and status == "EMPTY")
                print(f"  {status:5s} {panel['title'][:44]:44s} {target.get('legendFormat', '')[:24]:24s} {detail[:60]}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
