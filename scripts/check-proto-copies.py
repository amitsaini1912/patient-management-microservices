#!/usr/bin/env python3
"""
The .proto files copied between services (PLAN weakness #3, ADR 0035) must describe the SAME wire contract.
Each consumer keeps its own copy (no shared schema module yet); this check fails CI the moment a copy drifts.

Compared after normalising: comments, blank lines, whitespace runs and `option java_*` lines are ignored (they change
generated Java names, not the bytes on the wire). Everything else - package, messages, field names, numbers, types,
services, rpcs - must be identical.

    python3 scripts/check-proto-copies.py            # exit 1 + a diff if a copy differs from its owner
"""
import difflib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
# owner (the service that defines it) -> copies
COPIES = {
    "billing-service/src/main/proto/payment_event.proto": ["analytic-service/src/main/proto/payment_event.proto"],
    "billing-service/src/main/proto/billing_service.proto": ["patient-service/src/main/proto/billing_service.proto"],
    "patient-service/src/main/proto/patient_event.proto": ["analytic-service/src/main/proto/patient_event.proto"],
}


def contract(path: pathlib.Path) -> list[str]:
    text = re.sub(r"/\*.*?\*/", "", path.read_text(encoding="utf-8"), flags=re.S)   # block comments
    lines = []
    for line in text.splitlines():
        line = re.sub(r"//.*", "", line).strip()                                  # line comments
        line = re.sub(r"\s+", " ", line)
        if not line or re.match(r"option java_\w+\s*=", line):
            continue
        lines.append(line)
    return lines


def main() -> int:
    failed = False
    for owner, copies in COPIES.items():
        expected = contract(ROOT / owner)
        for copy in copies:
            actual = contract(ROOT / copy)
            if actual != expected:
                failed = True
                print(f"DRIFT: {copy} no longer matches {owner}")
                sys.stdout.writelines(line + "\n" for line in
                                      difflib.unified_diff(expected, actual, owner, copy, lineterm=""))
            else:
                print(f"ok    {copy} = {owner}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
