# ADR 0001: Choreographed saga with a transactional outbox

**Status:** accepted

## Context
A transfer touches two services that own separate databases. A distributed (2PC) transaction would couple them
tightly and block on the slowest participant; calling Kafka inside a DB transaction can lose or duplicate events.

## Decision
Model the transfer as a saga of local transactions coordinated by events (choreography). Every state change and the
event announcing it are written in **one local DB transaction** (transactional outbox); a relay publishes them to
Kafka. Failures after the debit are handled by an explicit compensating step.

## Consequences
+ No distributed locks, services stay independently deployable; atomic "change state and publish" without 2PC.
+ A compensating flow is first-class and tested.
- Eventual consistency: clients see `DEBIT_PENDING`/`CREDIT_PENDING` and poll or subscribe (WebSocket).
- The implicit flow gets hard to follow beyond ~4-5 steps; at that point move to a central orchestrator.
- Polling relay adds latency; Debezium/CDC is the production upgrade.
