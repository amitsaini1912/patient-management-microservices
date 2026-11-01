# ADR 0035 — Copied .proto files: a CI check instead of a shared schema module

- Status: Accepted
- Date: 2026-11-01
- PLAN weakness #3 (the `.proto` is copied by hand and the copies can drift).

## Context
Three protobuf contracts are copied from the service that owns them into the services that use them:
`payment_event.proto` (billing -> analytic-service), `billing_service.proto` (billing -> patient-service, gRPC),
`patient_event.proto` (patient-service -> analytic-service). A field renumbered in one copy compiles fine on both
sides and then silently misreads every message.

## Decision
`scripts/check-proto-copies.py`, run in CI (job `contracts`): every copy must describe the same wire contract as its
owner - package, messages, field names, numbers and types, services and rpcs. Ignored: comments, blank lines,
whitespace and `option java_*` lines (they only change generated Java class names; patient-service's copy has an
extra `java_outer_classname`). A drift fails CI with a diff.

## Alternatives
- A shared `contracts` Maven module (or a schema registry): the real fix, but the services have no parent POM and
  are built and deployed one by one; it would change every build. Not worth it for three small files today.
- Byte-for-byte comparison: fails on the harmless header comments and the Java option above.

## Consequences
- Changing a contract still means editing every copy by hand - but now CI says which ones you forgot.
- The check says nothing about COMPATIBILITY across versions (adding vs. renumbering fields); copies must simply match.
- Tested by hand: all three copies match today; renumbering one field in a copy -> exit 1 with the diff.
