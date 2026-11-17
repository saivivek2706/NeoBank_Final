# ADR 0002: Idempotency at both edges

**Status:** accepted

## Context
Kafka is at-least-once, and HTTP clients retry on timeouts and double-clicks. Either can move money twice.

## Decision
- **Consumers** record each handled `eventId` in `processed_events` in the same transaction as the side effect.
- **`POST /transfer`** requires an `Idempotency-Key`; `(requested_by, idempotency_key)` has a partial unique index and a
  SHA-256 fingerprint of the request detects key reuse with a different body (`422`). The unique index is the guard -
  application-level checks alone would race across replicas.
- The audit service relies on a unique index on `eventId` (index creation explicitly enabled).

## Consequences
+ Exactly-once *effect* on top of at-least-once delivery. A replay returns the original transaction.
- `processed_events` must outlive Kafka topic retention (14 d vs 7 d) and needs the retention job.
- Keys are scoped per user, so they can't be used to probe other users' transfers.
