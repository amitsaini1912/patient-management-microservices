# ADR 0033 — Anchoring the audit chain outside the database

- Status: Accepted
- Date: 2026-11-01
- Closes the gap ADR 0016 names: "DB access alone can silently cut off the newest rows".

## Context
The audit log (ADR 0016) is an HMAC chain: changing, deleting, inserting or reordering any row breaks it - except
deleting the NEWEST rows and moving `audit_chain_head` back. The rest is a valid, shorter chain, and verify said
"valid". Anyone with write access to billing's database could quietly undo the last minutes of the audit trail.

## Decision
- **Publish the head outside the database**: `AuditAnchors` sends `{seq, hash, at}` of `audit_chain_head` to Kafka
  topic `billing.audit.anchors` every 5 minutes (`billing.audit.anchor-every`), straight to Kafka - not through the
  outbox, which is in the same database. One partition, compacted, one key: the latest anchor is always kept.
- **Never publish backwards**: before publishing, the latest anchor is read from Kafka (not from memory, so a restart
  doesn't reset it). Head behind it -> refused (`REFUSED_HEAD_BEHIND`), same seq with another hash -> refused
  (`REFUSED_HASH_DIFFERS`); both are logged as `AUDIT TAMPERING?` and the anchor stays. Unchanged head -> nothing sent.
- **Verify against it**: `GET /audit/verify` reads the latest anchor and `AuditVerifier.verify(anchor)` adds two
  checks to the chain walk: the head must not be behind the anchor ("rows cut off the end and the head moved back"),
  and the row at the anchor's seq must still have the anchor's hash. The answer says which anchor was checked
  (`anchorSeq`), or why none was (`anchorNote`: none published yet / Kafka unreadable - the chain is still checked).
- `/audit` and `/audit/verify` are ADMIN only and routed at `/api/audit/**` (like ADR 0031).

## Consequences
- Rows appended after the last anchor (up to 5 minutes) can still be cut off unnoticed. Shorter interval = smaller
  window, more anchor messages (tiny, and compacted).
- The anchor is only as safe as Kafka's ACLs: someone who can write both billing's database AND that topic can still
  rewrite both. In production the topic would be writable by billing only and readable by an independent auditor
  (or the anchor also written to a WORM store / a second system).
- Someone with the HMAC key and the database can still forge a whole new chain from an old anchor on - but not past
  the latest anchor without the mismatch at its seq showing.
- Tests: `AuditAnchorIntegrationTest` (3, real Postgres + Kafka): the cut + rewind verifies "valid" without the
  anchor and is caught with it, and the publisher refuses to overwrite the evidence; a different hash at the
  published seq is refused, an unchanged head isn't republished, moving forward is; the endpoint reports the anchor
  and is ADMIN only. Break check: without the "head behind the anchor" check the first test fails.
