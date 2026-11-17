# Operations runbook

Each section matches an alert in `observability/alerts.yml`. Find any request across services with its
`X-Request-Id` (returned on every response): `docker compose logs | grep <id>`, or search the id / `traceId` in Zipkin.

## Dead-letter topics

**Alert:** `KafkaMessageDeadLettered` - a saga event failed all retries and was parked on `<topic>.DLT`.
The transfer it belongs to is **stuck** until the event is handled.

1. See what's parked: Kafka UI → topic `<topic>.DLT`, or
   `docker compose exec kafka kafka-console-consumer --bootstrap-server kafka:9092 --topic <topic>.DLT --from-beginning --property print.headers=true --max-messages 5`.
   Headers `kafka_dlt-exception-fqcn` / `kafka_dlt-exception-message` / `kafka_dlt-original-topic` say why.
2. Classify:
   - *Transient cause already fixed* (database was down, deploy in progress) → **replay** (step 3).
   - *Malformed payload / bug* → fix the producer or consumer first; replaying before that just dead-letters again.
3. Replay: consume the DLT and re-produce each record to `kafka-original-topic`
   (`kafka-console-consumer … | kafka-console-producer --topic <original topic>`). This is safe: consumers are idempotent
   (`processed_events`), so re-delivering an already-handled event is a no-op.
4. Confirm `neobank_kafka_dead_letters_total` stops increasing and the affected transaction reaches a terminal state.

## Dead outbox events

**Alert:** `OutboxEventDead` - an event exhausted its relay retries and never reached Kafka (`status = 'DEAD'`).

1. Find them: `SELECT id, topic, event_type, attempts, created_at FROM outbox_events WHERE status = 'DEAD';`
   (in `neobank_transaction` or `neobank_account`).
2. Fix the cause (Kafka unreachable, oversized/invalid payload) - the service logs carry the last send error.
3. Re-queue: `UPDATE outbox_events SET status = 'PENDING', attempts = 0 WHERE id = '<id>';` - the relay picks it up.
   Duplicates are harmless (idempotent consumers). DEAD rows are never purged automatically.

`OutboxBacklogGrowing` (pending > 500): Kafka is down/slow or the relay is not running; check `kafka` health and the
service logs for `OutboxRelay`.

## Ledger reconciliation drift

**Alert:** `LedgerReconciliationDrift` - an account's cached balance ≠ the sum of its ledger entries.

1. Logs of `account-service` (`LedgerReconciliationService`) name the account.
2. `GET /api/v1/accounts/{id}/reconcile` (as owner/admin) returns balance vs ledger sum.
3. **The ledger is the source of truth.** Investigate how the balance diverged (manual SQL edit? a bug?) before touching
   either side; correct the balance, never the ledger (append a compensating ledger entry if the ledger itself is wrong).

## Ledger integrity

**Alerts:** `LedgerUnbalanced`, `LedgerAccountDrift`, `TransferStuckInSettlement` (hourly audit, or on demand:
`GET /api/v1/ledger/trial-balance` as ADMIN - `healthy: false` lists exactly what failed).

- **Unbalanced journal / currency:** should be impossible (database trigger). If it fires, treat it as a data-integrity
  incident: stop transfers (scale `transaction-service` to 0), identify the journal id from the response, inspect its
  postings, and fix with a *reversing journal* - never an UPDATE (the table is append-only).
- **Account drift:** the cached `accounts.balance` differs from the postings. The **postings are the truth**. Find what
  changed the balance outside the saga (a manual SQL edit, a bug), then correct the cache to match the journal.
- **Stuck transfers:** a transfer was debited but neither credited nor reversed (`stuckInSettlement` lists the
  transaction ids; money sits in `SETTLEMENT`). Check the transaction's status and the dead-letter topics: usually a
  `*.credit.requested` / `*.debit.compensate` event is parked on a DLT - replay it. If the saga is gone, post the missing
  credit or reversal as an explicit journal.

## External reconciliation breaks

**Alert:** `ExternalReconciliationBreaksOpen` (unresolved for an hour). The settlement bank's statement is uploaded
per day/currency (`POST /api/v1/ledger/statements`, JSON or CSV) and compared with the ledger's `EXTERNAL_FUNDING`
movements by reference. Review `GET /api/v1/ledger/reconciliation-runs/{id}`:

| Break | Usual cause | Action |
|---|---|---|
| `MISSING_IN_LEDGER` | Money arrived that we didn't record (unsolicited credit, funding not yet booked) | Book the deposit, or return the funds; reference it |
| `MISSING_IN_STATEMENT` | We booked a movement the bank hasn't settled (timing, rejected payment) | Wait for next day's statement, or chase the bank |
| `AMOUNT_MISMATCH` | Fees netted by the bank, FX, keying error | Post the fee/adjustment as a journal, then resolve |
| `DIRECTION_MISMATCH` / `CURRENCY_MISMATCH` | Wrong side or currency booked | Reverse and re-post correctly |
| `DUPLICATE_REFERENCE_*` | Same reference twice on one side | Investigate before anything else - possible double payment |

Resolve each break with `POST /api/v1/ledger/reconciliation-breaks/{id}/resolve` and a note saying what was done. The
statement day is the UTC day of the posting.

## Holds

A customer reports money "missing": compare `balance` and `availableBalance` (`GET /api/v1/accounts/{id}/balance`) - the
difference is `heldAmount`. List them with `GET /api/v1/accounts/{id}/holds`; an operator can release one
(`POST .../holds/{holdId}/release`). Holds lapse by themselves after their ttl (default 7 days).

## Sanctions hits

**Alert:** `SanctionsStrongMatch` - a customer matched a list exactly and the account was frozen automatically.

1. Open the case: `GET /api/v1/compliance/cases?status=OPEN` (CRITICAL, type SANCTIONS_SCREENING) and read the history; the
   summary names the list, the listed name and whether it matched on a primary name or an alias.
2. Verify the identity against the listing (date of birth, nationality, address) - the name alone is not proof.
3. Decide:
   - *Same person* -> close with `ACCOUNT_RESTRICTED`; the account stays frozen. Follow your legal duty to report/freeze funds.
   - *Different person* -> `escalate` with the evidence, then a **second admin** closes it as `FALSE_POSITIVE` (four-eyes). Compliance
     then sends the unfreeze command and the customer is marked `CLEARED_FALSE_POSITIVE`.
4. A POTENTIAL_MATCH (fuzzy) case is HIGH priority and does **not** freeze; the same review applies, without the four-eyes step
   because no money was frozen.

Updating a list: `POST /api/v1/compliance/watchlist/import?mode=FULL` (CSV, see the API docs). Every customer is re-screened; the
response says how many were newly flagged. FULL mode deactivates entries of the lists it mentions that are missing from the file (a
delisting), DELTA only adds/updates. Each import is versioned with the file's SHA-256 - keep the original file with your records.

A cleared customer is only re-flagged by entries **added** in a later import, so a reviewed false positive does not come back.

## Compliance cases

**Alert:** `ComplianceCasesOverdue` - open cases are past their SLA (CRITICAL 24h, HIGH 72h, MEDIUM 7d, LOW 14d).

- `GET /api/v1/compliance/overview` for the headline numbers; `GET /api/v1/compliance/cases?assignee=<user>` for one reviewer's queue.
- A case moves OPEN -> IN_REVIEW (`assign`) -> ESCALATED (`escalate`, needs a note) -> CLOSED (`close` with a disposition and a note).
- **Four-eyes:** `SAR_FILED`, and clearing a sanctions hit that froze the account, need the case ESCALATED and closed by someone other
  than the user who escalated it (HTTP 409 otherwise). Filing the SAR with the regulator itself happens outside this system;
  record the report reference in the closing note.
- New alerts on an account with an open case attach to it (and can raise its priority and pull the due date earlier).
- AML thresholds are configuration (`AML_LARGE_TRANSACTION_THRESHOLD`, `AML_STRUCTURING_MIN_COUNT`, `AML_HIGH_VELOCITY_COUNT`, ...).
  Tune them from the alert volume on the dashboard; a rule that fires constantly is noise, one that never fires is not watching.
  Monetary thresholds apply to every currency as a plain number - convert to a reporting currency before production use.

## Audit chain integrity

**Alert:** `AuditChainBroken` (`neobank_audit_chain_valid == 0`) - the audit trail failed verification: possible tampering.

1. `GET /api/v1/audit/chain/verify` (ADMIN, whole chain) names the **first broken record** and why:

   | Reason | Meaning |
   |---|---|
   | `HASH_MISMATCH` | that record's content was changed after it was written |
   | `BROKEN_LINK` | a record points at a different predecessor than the one actually there (a record was swapped or re-sealed) |
   | `SEQUENCE_GAP` | a record was deleted (or inserted out of order) |
   | `CHECKPOINT_MISMATCH` / `CHECKPOINT_AHEAD` | history before a checkpoint was rewritten, or the tail was truncated |

2. Treat it as a security incident: preserve the database, identify who had write access to the `audit_events` collection, compare
   against the externally stored chain heads (below), and restore the records from the Kafka topics / backups.
3. **Anchor the head outside the database.** On a schedule, copy `GET /api/v1/audit/chain/head` (sequence + hash) to storage the
   database administrators cannot modify (object storage with retention lock, a signed email, a ticket). That is what lets you prove
   later that history was not rewritten even by someone who controlled the entire database.

Records written before the chain existed are reported as `unchainedLegacyEvents`; they cannot be verified and are never altered.

## Stuck transfers

A transfer that stays in `DEBIT_PENDING` / `CREDIT_PENDING` / `COMPENSATING` for more than a minute:
1. Check the DLT topics and dead outbox events above - these are the usual causes.
2. Check consumer lag in Kafka UI for the `account-service` / `transaction-service` groups.
3. `docker compose logs transaction-service account-service | grep <transactionId>`.

## Low success ratio

`TransferSuccessRatioLow`: open the *Transfer Saga* dashboard. *Failures by stage* tells you where: `debit` is mostly
legitimate (insufficient funds); `credit` means debits are being compensated - look at the receiving side
(frozen/closed accounts, account-service errors).

## Secrets and keys

- Local/demo secrets: `./scripts/bootstrap-env.sh --force` regenerates everything. **Data encrypted with the old
  `PII_ENCRYPTION_KEY_B64` becomes unreadable** - only do this on a throwaway environment (`docker compose down -v`).
- JWT key rotation: publish the new public key under a new `JWT_KEY_ID` in the JWKS alongside the old one until existing
  tokens expire, then remove the old key. (Automated rotation is not implemented.)
- Rotating the PII key requires re-encrypting existing rows - not a configuration-only change.

## Routine

- Retention: outbox `SENT` rows after 72 h, `processed_events` after 14 d (`maintenance.*` properties, daily 03:15/03:30).
- Swagger UI can be disabled with `SWAGGER_UI_ENABLED=false`.
