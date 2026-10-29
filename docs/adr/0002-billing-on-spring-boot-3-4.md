# ADR 0002 — billing-service on Spring Boot 3.4.1 (same as the other services)

- Status: Accepted
- Date: 2026-10-29

## Context
billing-service was generated on Spring Boot 4.0.6 while patient, auth, analytic and gateway use 3.4.x.
billing-service is about to become the biggest service (ledger, payments, Kafka outbox, Resilience4j,
gRPC). At this point it contained only a stub, so changing version cost almost nothing.

## Decision
Move billing-service to Spring Boot **3.4.1**. New services in this repo (card-vault,
payment-network-simulator) also start on 3.4.1.

## Alternatives considered
- **Keep 4.0.6 in billing only.** Mixed majors are legal in microservices, but the libraries we need next
  (`resilience4j-spring-boot3`, `net.devh` gRPC starter, springdoc 2.x) are built for Boot 3; Boot 4 brings
  Jackson 3 and re-split starters. Two sets of rules to debug and to explain.
- **Upgrade every service to Boot 4 now.** The right long-term move, but a migration project of its own
  that adds no payments value today.

## Consequences
- + One framework version, one set of docs, the same test setup in every service.
- − Spring Boot 3.4 is past its open-source support window. Recorded as a known limitation; the
  upgrade to Boot 4 for all services together is on the roadmap.
