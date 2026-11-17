# ADR 0006: A separate compliance service, event-driven, with maker-checker

**Status:** accepted

## Context
A bank must screen customers against sanctions lists, monitor transactions for money-laundering patterns, and be able to show
what it did about each finding. These duties have different owners, retention rules and change cadence from payments or fraud,
and an outage in them must not take payments down - nor may a payment ever skip them silently.

## Decision
- **Own service and database** (`compliance-service`), fed by the existing Kafka events: `account.opened` (new, published by the
  account service) for customer screening, and `account.debit.completed` / `account.credit.completed` for transaction monitoring.
  No synchronous call sits in the payment path, so a compliance outage delays *detection*, never payments, and no event is lost
  (the outbox/idempotent-consumer/dead-letter pattern is the same as everywhere else).
- **Sanctions screening** runs at onboarding and again for every customer whenever a list is imported (a clean customer can be
  listed tomorrow). Every transfer is between customers of this bank, so screening the customer base on those two triggers covers
  both counterparties. An external payment rail would add real-time counterparty screening behind the same matcher.
- **Two-tier matching.** `STRONG_MATCH` = identical after normalisation (case, accents, punctuation, word order): the account is
  **frozen automatically** (compliance sends a `compliance.account.action` command the account service obeys) and a CRITICAL case
  opens. Anything fuzzy is *capped below 1.0* so it can only reach `POTENTIAL_MATCH`: a human reviews it, nothing is frozen unless
  the policy flag says so. Auto-freezing on a fuzzy score would freeze innocent customers.
- **Evidence.** Every screening run is stored (name, list version, matches, score) in an append-only table; every list import is a
  checksummed version. A cleared customer is only re-flagged by entries *added* later, so the same false positive does not return.
- **Transaction monitoring** runs pure, unit-tested rules (large transaction, structuring just below the threshold, rapid
  pass-through, high velocity) over the account's recent history. Each alert has a dedup key, so a burst yields one alert; alerts are
  grouped into **one open case per account and type** (enforced by a partial unique index).
- **Case management** is a state machine owned by the entity (OPEN -> IN_REVIEW -> ESCALATED -> CLOSED) with SLA due dates by
  priority and an append-only history of who did what and why. **Maker-checker (four-eyes):** filing a SAR, or clearing a sanctions
  hit that froze an account, requires the case to be escalated and closed by a *different* user than the escalator.
- **Audit.** The audit service now also records account openings and compliance commands, and its trail is a **SHA-256 hash chain**
  (gapless sequence, each record hashes its content plus the previous hash) with periodic checkpoints and a verification API, so
  editing, deleting or truncating records is detectable.

## Consequences
+ Detection is decoupled from payments; every decision is explainable and attributable.
+ Compliance policy (thresholds, review score) is configuration, not code.
- Detection is asynchronous: a listed customer can briefly have an ACTIVE account between the event and the freeze (seconds).
  Pre-account-opening screening would close that gap.
- Name matching is a linear in-memory scan with simple fuzzy logic: fine for a consolidated list, not a replacement for a vendor
  engine (transliteration, dates of birth, addresses) at scale. The `WatchlistIndex` is the seam to swap that in.
- Only one privileged role (ADMIN) exists, so four-eyes is enforced between *users*, not between *roles*.
