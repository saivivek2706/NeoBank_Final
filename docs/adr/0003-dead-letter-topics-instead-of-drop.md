# ADR 0003: Dead-letter topics for poison messages

**Status:** accepted

## Context
Spring Kafka's default after a few immediate retries is to log and drop the record. In a payments flow a dropped saga
event silently strands a transfer.

## Decision
Every consuming service installs a `DefaultErrorHandler`: exponential backoff, non-retryable classification for
malformed payloads, then publish to `<topic>.DLT` with the failure in headers, and count it in
`neobank_kafka_dead_letters_total` (alerted). Business-level "cannot succeed" conditions (e.g. credit to an unknown
account) are **not** left to the DLT - they emit an explicit `*.failed` event so the saga compensates.

## Consequences
+ Nothing is dropped; one bad record never blocks a partition; replay is possible after a fix (see runbook).
- Operators must watch the DLT alert; replay is manual by design.
