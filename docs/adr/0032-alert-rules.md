# ADR 0032 — Prometheus alert rules, unit-tested with promtool

- Status: Accepted
- Date: 2026-11-01
- Builds on ADR 0020 (metrics + dashboard). Closes the "no alert yet" notes left by Days 6a, 20b and the follow-ups.

## Context
Every hard failure the project found has a metric: permanently refused outbox events, outbox lag, the risk breaker,
the fallback rate (Day 22: 66% of payments silently not risk-checked while k6 showed 0 errors), unresolved
stale payments, rejected webhooks, account lockouts, open Redis breakers. Nothing watched them: a person had to look
at the dashboard at the right moment.

## Decision
`monitoring/alerts.yml`, loaded by Prometheus (`rule_files`, mounted in compose), 11 rules in 4 groups (12 since ADR 0034
added `ReviewQueueWaiting`):

| Alert | Fires when | Severity |
|---|---|---|
| BillingOutboxEventRejected | any outbox row FAILED for 1 min | critical |
| BillingOutboxLagging | oldest unsent payment event > 60 s for 2 min | warning |
| PatientOutboxBacklog | > 100 patient events unsent for 5 min | warning |
| StalePaymentsUnresolved | status inquiries keep failing for 15 min | warning |
| StaleAuthorizationNotVoided | an orphan hold could not be voided | warning |
| RiskEngineCircuitOpen | billing's risk breaker open for 1 min | warning |
| RiskFallbackRateHigh | > 20% of payments decided without the engine for 5 min (`disabled` not counted) | warning |
| WebhookSignatureFailures | > 5 rejected webhooks in 10 min | warning |
| LoginLockoutSpike | > 10 accounts locked in 10 min | warning |
| RateLimitsNotEnforced | a rate limiter's Redis breaker open for 2 min | warning |
| ServiceDown | a scrape target not answering for 1 min | critical |

Each has a `summary` (with the value) and an `action` (where to look first). Thresholds are first guesses from the
load/chaos runs (normal = 0 for all but the fallback rate and outbox lag, which are 0 / sub-second under load).

`monitoring/alerts.test.yml`: promtool unit tests - every alert fires on its failure with the expected labels and
text, and stays quiet just below its threshold / before its `for` (e.g. 10% fallback, or 600 "disabled" decisions,
don't fire). CI job `monitoring` runs `promtool check rules` + `test rules`. Break check: counting `disabled` as a
fallback makes the tests fail.

## Consequences
- **No Alertmanager**: alerts fire in Prometheus (http://localhost:9090/alerts) and nobody is notified. Next step: an
  Alertmanager with a receiver (email/Slack/PagerDuty), routing critical vs warning.
- No alert on reconciliation breaks or dead-letter topics yet: there is no metric for either (recon reports are rows
  in the DB; the DLTs are Kafka topics nobody consumes).
- Thresholds are not tuned on real traffic; k8s has no Prometheus (compose only).
