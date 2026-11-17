# NeoBank — Event-Driven Digital Banking Platform

A microservices-based banking backend demonstrating production patterns used
by real fintechs: the **Saga pattern** for distributed transactions, the
**Outbox pattern** for reliable event publishing, **Kafka** for asynchronous
service-to-service communication, and **live WebSocket updates** for a
real-time frontend experience.

> **Status: Feature-complete (Phases 1-3).** Core money-movement saga, the
> event-driven Notification/Fraud/Audit fan-out, a React frontend with live
> WebSocket updates, distributed tracing, Kubernetes manifests, CI/CD, and a
> real test suite are all built. See [What's tested](#whats-tested) and
> [Observability & deployment](#observability--deployment) below.

---

## Why this architecture

Real banks can't do a simple `UPDATE balance` across two accounts in one SQL
transaction once Account and Transaction live in different services with
different databases (this is the norm at scale — see
[Database-per-service](https://microservices.io/patterns/data/database-per-service.html)).
Instead they use a **saga**: a sequence of local transactions coordinated by
events, with explicit compensating actions if something fails partway through.

```
POST /transfer  ──►  transaction-service
                          │
                          │ (1) writes Transaction=DEBIT_PENDING + outbox row, same DB tx
                          ▼
                  [outbox_events table] ──(poller, every 1s)──► Kafka: account.debit.requested
                                                                          │
                                                                          ▼
                                                              account-service debits sender
                                                                          │
                                                     ┌────────────────────┴───────────────────┐
                                                     ▼                                         ▼
                                        Kafka: account.debit.completed          Kafka: account.debit.failed
                                                     │                                         │
                                                     ▼                                         ▼
                                     transaction-service: CREDIT_PENDING          transaction-service: FAILED
                                     publishes account.credit.requested          (nothing to compensate - sender
                                                     │                            was never actually debited)
                                                     ▼
                                        account-service credits receiver
                                                     │
                              ┌──────────────────────┴──────────────────────┐
                              ▼                                             ▼
                Kafka: account.credit.completed              Kafka: account.credit.failed
                              │                                             │
                              ▼                                             ▼
              transaction-service: COMPLETED             transaction-service: COMPENSATING
              publishes transaction.completed             publishes account.debit.compensate
                                                            (reverses the original debit)
                                                                       │
                                                                       ▼
                                                            transaction-service: FAILED
```

Every arrow above is a real Kafka topic, keyed by `accountId` so all events
for one account are processed strictly in order.

### The two patterns that make this safe

**Outbox pattern** — every service writes its "I did X" event to a DB table
called `outbox_events` in the *same local transaction* as the business
change (e.g. debiting a balance). A separate scheduled poller (`OutboxRelay`)
reads pending rows and publishes them to Kafka, marking them sent only after
the broker acknowledges. This closes the classic "dual-write" gap where a DB
commit succeeds but the Kafka publish is lost (or vice versa) if the process
crashes in between — unacceptable when the "event" is "I just moved money."

**Idempotent consumers** — Kafka is *at-least-once* delivery, not exactly-once.
Every consumer checks a `processed_events` table (keyed by a UUID `eventId`
generated once at the source) before acting, and records it afterwards, in the
same transaction as the business change. So a redelivered message after a
broker hiccup or consumer rebalance is a safe no-op instead of a double-debit.

Both are visible in `account-service`'s and `transaction-service`'s
`domain/OutboxEvent.java`, `domain/ProcessedEvent.java`, and `kafka/` packages
— the code comments walk through the reasoning inline.

---

## Services

| Service | Port | Responsibility |
|---|---|---|
| `eureka-server` | 8761 | Service registry — every service registers here and discovers peers by name |
| `config-server` | 8888 | Centralized config (native/file-backed for local dev; swap for a Git backend in prod) |
| `api-gateway` | 8000 | Single entry point, JWT validation at the edge, routing, circuit breakers |
| `auth-service` | 8080 | Registration/login, issues JWTs (RS256, verified via JWKS), Redis-backed refresh tokens, account lockout |
| `account-service` | 8081 | Account CRUD, balance, saga participant (debit/credit/compensate) |
| `transaction-service` | 8082 | Initiates transfers, saga orchestration via choreography, WebSocket live updates |
| `notification-service` | 8083 | Consumes debit/credit/failure events, simulates account alerts |
| `fraud-service` | 8084 | Consumes debit events, screens against rule-based checks, live alert feed |
| `audit-service` | 8085 | Consumes every saga event, account opening and compliance command into an immutable MongoDB log that is also a **tamper-evident SHA-256 hash chain** |
| `compliance-service` | 8086 | **Sanctions screening**, **AML transaction monitoring** and **case management** with maker-checker approval |
| `frontend` | 3000 (Docker) / 5173 (dev) | React + TypeScript — accounts, transfers, live saga status, fraud dashboard |

Infra: **PostgreSQL** (one database per service, except Audit), **MongoDB**
(audit-service — deliberately different, see below), **Kafka** (KRaft mode,
no Zookeeper), **Redis** (refresh tokens + fraud rate-limiting counters),
**Kafka UI** (inspect topics/messages in a browser at `localhost:8090`).

### Why Audit uses MongoDB instead of Postgres

This is "polyglot persistence" in action: an append-only, schema-flexible,
write-heavy audit log queried by document shape (not joins) is a natural fit
for a document store, and it's a common real choice for exactly this
use-case. It also means `audit-service` can evolve its event schema freely
without a migration, since every saga event type has slightly different
fields.

### The Notification/Fraud/Audit fan-out

All three Phase 2 services subscribe independently to the same underlying
Kafka topics — this is the "one event, many consumers" pattern that's the
biggest practical win of using Kafka over point-to-point calls:

```
account.debit.completed ──┬──► notification-service (sends a debit alert)
                           ├──► fraud-service (screens the amount + frequency)
                           └──► audit-service (records it, alongside every other topic)
```

Each consumer has its own consumer group, so all three receive every message
independently — none of them compete with each other for partitions, and
adding a fourth consumer later (say, a `credit-scoring-service`) requires
zero changes to any existing service.

---

## Running it locally

**Prerequisites:** Docker + Docker Compose v2. That's it — Maven and Java run
*inside* the build containers, you don't need them installed locally.

```bash
git clone <this repo> && cd <repo-folder>
./scripts/bootstrap-env.sh      # once: writes .env with fresh random secrets (never committed)
docker compose up --build
```

`bootstrap-env.sh` generates the RSA signing keypair, the AES/HMAC keys, the
database passwords, the Grafana password and the first admin login, and prints
the admin credentials at the end. **No secret lives in the repository**, and
the services refuse to start without them rather than falling back to a
well-known default. `make up` does both steps.

First build takes a few minutes (Maven dependencies + base images); later
builds are much faster. `docker compose up` waits on real health checks, so
services start in dependency order.

Once everything is healthy:
- **Frontend (the actual app to click through)**: http://localhost:3000
- API Gateway (single entry point for all API calls): http://localhost:8000
- **API docs (Swagger UI for every service)**: http://localhost:8000/swagger-ui.html
- Eureka dashboard: http://localhost:8761
- Zipkin traces: http://localhost:9411
- With the default `COMPOSE_PROFILES=observability` from `.env`: Kafka UI http://localhost:8090,
  Prometheus http://localhost:9090, Grafana http://localhost:3001 (login in `.env`)

**Running the frontend outside Docker** (faster iteration while developing):
```bash
cd frontend
npm ci
npm run dev
```
Then open http://localhost:5173 — Vite's dev server proxies `/api` and `/ws`
straight to the gateway on port 8000 (see `vite.config.ts`), so make sure the
backend stack is up first.

`make help` lists the other shortcuts (`make verify`, `make e2e`, `make load-test`, …).

### Project layout

```
api-gateway/ auth-service/ account-service/ transaction-service/
notification-service/ fraud-service/ audit-service/
compliance-service/                                   Spring Boot services
eureka-server/ config-server/                         platform services
e2e-tests/        full-stack Testcontainers saga test (mvn -Pe2e verify -pl e2e-tests)
frontend/         React + TypeScript
k8s/              Kubernetes manifests          observability/  Prometheus, alert rules, Grafana
load-tests/       k6 scripts                    docs/           architecture, runbook, ADRs
scripts/          bootstrap-env.sh, k8s-secrets.sh, coverage-summary.py
```

Deeper docs: [Architecture](docs/architecture.md) · [Operations runbook](docs/runbook.md) ·
[Decision records](docs/adr/) · [Load tests](load-tests/README.md) · [Kubernetes](k8s/README.md)

---

## Demo walkthrough

**The easy way: use the frontend.** Open http://localhost:3000, register two
accounts (e.g. Alice and Bob, in two browser tabs or one normal + one
incognito window so both sessions stay logged in), open a bank account for
each from the dashboard, then use **Transfer** to send money from Alice to
Bob. You'll land on a live status page that visibly steps through
`DEBIT_PENDING → CREDIT_PENDING → COMPLETED` as Kafka events land — no
refresh needed. Check the **Fraud Watch** tab and try a transfer over 10,000
to see a live fraud alert appear the instant it's flagged.

**The API way** (useful for understanding exactly what's happening under the
hood, or for automating a demo):

All requests go through the gateway on port 8000.

**1. Register two users**
```bash
curl -X POST http://localhost:8000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@neobank.dev","password":"password123","fullName":"Alice Tan"}'

curl -X POST http://localhost:8000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"bob@neobank.dev","password":"password123","fullName":"Bob Lee"}'
```
Tokens are issued as httpOnly cookies (`Set-Cookie: access_token=…`); the gateway also accepts
them as `Authorization: Bearer`, which is easiest from curl. Pull the token and `userId` out of each response:
```bash
ALICE=$(curl -si -X POST http://localhost:8000/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"alice@neobank.dev","password":"password123"}')
ALICE_TOKEN=$(echo "$ALICE" | grep -i '^set-cookie: access_token=' | sed 's/.*access_token=\([^;]*\).*/\1/')
```
(Register also returns `userId` in the JSON body.)

**2. Open an account for each user**
```bash
curl -X POST http://localhost:8000/api/v1/accounts \
  -H "Authorization: Bearer <ALICE_TOKEN>" -H "Content-Type: application/json" \
  -d '{"ownerId":"<ALICE_USER_ID>","ownerName":"Alice Tan","accountType":"SAVINGS","currency":"SGD","openingBalance":1000}'

curl -X POST http://localhost:8000/api/v1/accounts \
  -H "Authorization: Bearer <BOB_TOKEN>" -H "Content-Type: application/json" \
  -d '{"ownerId":"<BOB_USER_ID>","ownerName":"Bob Lee","accountType":"SAVINGS","currency":"SGD","openingBalance":0}'
```
Save each `id` from the response — these are the account UUIDs.

**3. Transfer money (this triggers the full saga)**
```bash
curl -X POST http://localhost:8000/api/v1/transactions/transfer \
  -H "Authorization: Bearer <ALICE_TOKEN>" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId":"<ALICE_ACCOUNT_ID>","toAccountId":"<BOB_ACCOUNT_ID>","amount":250,"currency":"SGD"}'
```
The `Idempotency-Key` header is **required** (see [Idempotent transfers](#idempotent-transfers)): re-run the exact
same command with the same key and you get the original transaction back, not a second transfer.

Response comes back `202 Accepted` immediately with status `DEBIT_PENDING` —
the saga is running asynchronously. Watch it complete:

```bash
curl http://localhost:8000/api/v1/transactions/<TRANSACTION_ID> \
  -H "Authorization: Bearer <ALICE_TOKEN>"
```
Poll this a couple of times and watch `status` move
`DEBIT_PENDING → CREDIT_PENDING → COMPLETED`. Or open **Kafka UI**
(`localhost:8090`) and watch the messages land on `account.debit.requested`,
`account.debit.completed`, `account.credit.requested`, `account.credit.completed`,
`transaction.completed` in real time — and notice `notification-service`,
`fraud-service`, and `audit-service` all consuming from the same topics
independently.

**4. Check the fan-out**

Notifications are visible to the account owner. Fraud alerts and the audit
trail are **admin-only** (see [RBAC](#role-based-access-control) below) — log
in as the bootstrap admin first (credentials were printed by `bootstrap-env.sh` and are in `.env`):
```bash
source <(grep '^ADMIN_BOOTSTRAP' .env)
ADMIN_TOKEN=$(curl -si -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ADMIN_BOOTSTRAP_EMAIL\",\"password\":\"$ADMIN_BOOTSTRAP_PASSWORD\"}" \
  | grep -i '^set-cookie: access_token=' | sed 's/.*access_token=\([^;]*\).*/\1/')

curl http://localhost:8000/api/v1/notifications/account/<ALICE_ACCOUNT_ID> -H "Authorization: Bearer <ALICE_TOKEN>"
curl http://localhost:8000/api/v1/audit/transaction/<TRANSACTION_ID> -H "Authorization: Bearer $ADMIN_TOKEN"
curl http://localhost:8000/api/v1/fraud-alerts -H "Authorization: Bearer $ADMIN_TOKEN"
```

**5. Trigger a fraud flag**
```bash
curl -X POST http://localhost:8000/api/v1/transactions/transfer \
  -H "Authorization: Bearer <ALICE_TOKEN>" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId":"<ALICE_ACCOUNT_ID>","toAccountId":"<BOB_ACCOUNT_ID>","amount":15000,"currency":"SGD"}'
```
(Make sure Alice's balance covers it first.) Check `/api/v1/fraud-alerts`
with the admin token — you'll see a `LARGE_AMOUNT` / `HIGH` severity alert,
and if the frontend's Fraud Watch tab is open (log in as
the bootstrap admin), it appears there live over WebSocket.

**6. Watch a compensation happen (the interesting failure case)**

Try transferring more than the sender's balance — the debit itself fails
(insufficient balance), so `status` goes straight to `FAILED` with no
compensation needed (nothing was ever moved). To see the *compensation*
branch specifically (debit succeeds, credit fails), either send the transfer to a
non-existent `toAccountId`, or freeze the receiving account first via a direct DB update
(`UPDATE accounts SET status='FROZEN' WHERE id=...` against the
`neobank_account` database), and retry a valid transfer — you'll see
`COMPENSATING` in the transaction status and the sender's balance restored,
exactly as documented in `TransferService.java`.

---

## What to say about this in an interview

- **"Walk me through your hardest technical problem"** → the outbox pattern +
  why naive `save() then kafkaTemplate.send()` is broken, with the specific
  failure mode it prevents.
- **"How do you handle a distributed transaction across services?"** → the
  saga diagram above, orchestration vs. choreography trade-off (this is
  choreography — each service reacts independently; explain you'd switch to
  a central orchestrator if the number of saga steps grew past ~4-5, since
  choreography's implicit flow gets hard to trace).
- **"What happens if Kafka redelivers a message?"** → idempotent consumers via
  `processed_events`, and why exactly-once processing is achievable on top of
  an at-least-once broker.
- **"How would this scale?"** → partition key = accountId keeps per-account
  ordering while allowing horizontal scale-out across partitions; mention
  Debezium/CDC as the production upgrade from the polling outbox relay.

---

## What's tested

| Layer | What it proves |
|---|---|
| **Unit tests** (JUnit 5 + Mockito + AssertJ) in every service | Domain invariants, every saga transition incl. the **compensation branch**, idempotent re-processing, fraud rules, auth paths, gateway filters, dead-letter routing, metrics emitted by the saga |
| **Testcontainers: Postgres** (`account-service`, `transaction-service`) | Real Postgres + the production Flyway migrations (no H2): entity/schema drift, optimistic locking, and that the **idempotency unique index** really rejects a duplicate transfer; outbox/`processed_events` retention queries |
| **Testcontainers: MongoDB** (`audit-service`) | The unique index on `eventId` exists and a redelivered event is stored once; the **hash chain stays gapless and linear under 8 concurrent writers**, and editing, deleting or truncating records directly in Mongo is detected |
| **Testcontainers: Postgres** (`compliance-service`) | One open case per account and type, **append-only case history and screening evidence**, alert de-duplication, optimistic locking - against the real schema with Hibernate `validate` |
| **Compliance unit tests** | Name matching (exact vs fuzzy tiers, calibrated thresholds), every AML rule at its boundaries, the case state machine and **four-eyes rule**, screening outcomes and freeze/unfreeze decisions, watchlist import (add/change/delist) |
| **Smoke tests** (`eureka-server`, `config-server`) | The platform services boot, serve config/registry, and expose health + Prometheus endpoints |
| **End-to-end** (`e2e-tests`, Testcontainers + docker-compose) | The whole stack through the gateway: happy path, idempotent replay, key misuse (422), missing key (400), insufficient funds, **compensation** when the credit fails, a **balanced trial balance with an empty SETTLEMENT**, **holds** blocking spend, and **external statement reconciliation** surfacing exactly the planted difference |
| **Lint & coverage** | Checkstyle runs in `mvn validate`; JaCoCo writes `target/site/jacoco/` per module (summary in the CI job); ESLint + `tsc` for the frontend |

Run: `mvn verify` from the repo root (lint + unit + integration + coverage; needs a Docker daemon for
Testcontainers). The full-stack suite is separate because it is slow: `./scripts/bootstrap-env.sh && mvn -Pe2e verify -pl e2e-tests`.

---

## Observability & deployment

### Distributed tracing

Every service is wired with **Micrometer Tracing + OpenTelemetry**, exporting
to **Zipkin** (`localhost:9411` in Docker Compose). Because the `traceId` is
injected into every log line (see the `logging.pattern.level` in each
`application.yml`), you can take one `traceId` from a Zipkin trace and grep
every service's logs for it to reconstruct exactly what happened across the
whole saga for one transfer — the single most useful debugging capability in
a microservices system, and a very standard "tell me about a time you
debugged a distributed system" interview answer.

Open http://localhost:9411 after making a transfer and search for a trace —
you'll see the request hop through the gateway, into `transaction-service`,
and the Kafka-driven calls into `account-service` as connected spans.

### Metrics

Every service exposes `/actuator/prometheus` (the `micrometer-registry-prometheus` dependency is what makes
that endpoint exist - `management.endpoints.web.exposure` alone is not enough). **Prometheus** scrapes all of
them (`observability/prometheus.yml`) and **Grafana** auto-loads two dashboards
(`observability/grafana/provisioning/`):

- *NeoBank - Service Overview*: request rate, p95 latency, JVM heap, 5xx rate per service.
- *NeoBank - Transfer Saga*: transfers started / completed / failed, success ratio, **compensations**,
  saga duration p50/p95/p99 (POST → COMPLETED across Kafka), failures by stage, **outbox backlog and DEAD events**,
  **dead-lettered Kafka messages**, idempotent replays, ledger-reconciliation drift.

Business metrics: `neobank_transfers_{started,completed,failed,compensated,idempotent_replays}_total`,
`neobank_transfers_saga_duration_seconds`, `neobank_outbox_{pending,dead}`, `neobank_kafka_dead_letters_total`,
`neobank_ledger_reconciliation_drift_total`. `observability/alerts.yml` turns the dangerous ones into
Prometheus alerts (dead outbox events, dead-lettered messages, ledger drift, low success ratio, service down);
see [docs/runbook.md](docs/runbook.md) for what to do when each fires.

- Prometheus: http://localhost:9090 (alerts at `/alerts`)
- Grafana: http://localhost:3001 (credentials are generated into `.env`; anonymous access is off)

### Logging & request correlation

The gateway stamps every request with an `X-Request-Id` (or keeps a well-formed one from the caller), every
service puts it in the logging MDC, and the id is returned to the client. A user-reported failure can therefore be
traced across all services with one grep, alongside the Zipkin `traceId`. In docker-compose the services log
**structured JSON (Elastic Common Schema)** - one object per line with `traceId` and `requestId` - ready for
Loki/ELK; set `LOG_FORMAT=` (empty) in `.env` for plain-text console logs.

### API documentation

Every service publishes an OpenAPI 3 spec (`/v3/api-docs`) and Swagger UI (`/swagger-ui.html`); the gateway
aggregates all of them at http://localhost:8000/swagger-ui.html. Disable in production with `SWAGGER_UI_ENABLED=false`.

### Kubernetes

Full manifests are in `k8s/` — namespace, ConfigMap (the Secret is generated, never committed: `scripts/k8s-secrets.sh`), StatefulSets for
Postgres/MongoDB/Kafka, Deployments + Services + HorizontalPodAutoscalers for
every application service, and an optional Ingress. See `k8s/README.md` for
build/load/deploy instructions (works with kind or minikube locally). The
account-service and transaction-service Deployments include HPAs scaling on
CPU, since those two see the most traffic in the saga.

### CI/CD

`.github/workflows/ci.yml`, on every push/PR to `main`:
1. **Backend** - `mvn verify` over the whole reactor: checkstyle, unit + Testcontainers integration tests, JaCoCo
   coverage (summary on the run page, reports as artifacts).
2. **Frontend** - `npm ci`, ESLint, `tsc -b` + Vite build.
3. **Security** - Trivy scan for vulnerable dependencies and committed secrets (fails on CRITICAL/HIGH),
   report-only scan of Dockerfiles/manifests, and GitHub dependency review on PRs.
4. **Docker** - builds every image, Trivy-scans it, and pushes to GHCR only on `main`.
5. **E2E** - the full-stack saga test, on `main`, manual dispatch, or PRs labelled `e2e`.

Dependabot keeps Maven, npm, GitHub Actions and base images current.

### Compliance: sanctions screening, AML monitoring and cases

Handled by `compliance-service`, driven entirely by Kafka events - it is never in the payment path, so a compliance outage delays
*detection*, not payments, and nothing is lost ([ADR 0006](docs/adr/0006-compliance-service.md)).

**Sanctions screening.** Each account owner is screened at onboarding (from the `account.opened` event) and every customer is
re-screened whenever a watchlist is imported (`POST /api/v1/compliance/watchlist/import`, CSV). Names are normalised (case, accents,
punctuation, word order) and scored in two tiers:

| Result | Meaning | What happens |
|---|---|---|
| `STRONG_MATCH` | identical to a listed name or alias | the account is **frozen automatically** (a command the account service obeys) and a CRITICAL case opens |
| `POTENTIAL_MATCH` | a close fuzzy match (score ≥ 0.88, capped below 1.0) | a HIGH case opens for a person to decide; **nothing is frozen** unless `SANCTIONS_FREEZE_ON_POTENTIAL_MATCH=true` |
| `CLEAR` | no match | nothing - but the run is still stored as evidence |

Every screening is recorded (name, list version, matches, score) in an append-only table and every list import is a checksummed
version, so any decision can be reconstructed. A customer cleared as a false positive is only re-flagged by entries *added* later.

**AML transaction monitoring.** Pure, configurable rules over each account's recent history: `LARGE_TRANSACTION` (at/above the
reporting threshold), `STRUCTURING` (several transfers each just under it that add up past it), `RAPID_MOVEMENT` (money in and
almost immediately out again) and `HIGH_VELOCITY` (unusually many outgoing transfers per hour). Each alert is de-duplicated per
rule/window and grouped into **one open case per account**.

**Case management.** `OPEN → IN_REVIEW → ESCALATED → CLOSED`, with an SLA due date by priority and an append-only history of who did
what and why (`GET /api/v1/compliance/cases/{id}`). **Maker-checker:** filing a SAR, or clearing a sanctions hit that froze an
account, requires the case to be escalated and then closed by a *different* user than the one who escalated it (HTTP 409
otherwise). The runbook explains each step: [sanctions hits](docs/runbook.md#sanctions-hits),
[cases](docs/runbook.md#compliance-cases).

**Tamper-evident audit trail.** The audit service links every record into a SHA-256 hash chain (gapless sequence; each record
hashes its content plus the previous hash). `GET /api/v1/audit/chain/verify` recomputes it and names the first record that was
edited, deleted or re-sealed; hourly checkpoints also catch truncation of the tail, and `GET /api/v1/audit/chain/head` is what you
copy to storage outside the database. `neobank_audit_chain_valid` feeds an alert. See
[audit chain integrity](docs/runbook.md#audit-chain-integrity).

What this deliberately does **not** do: it has no real list feed (you import CSVs), no external SAR filing (the closing note records
the report reference), no date-of-birth/address matching, and monetary thresholds are plain numbers across currencies. Those are the
integration points a licensed deployment would fill.

### Double-entry ledger, fees, holds and reconciliation

Money correctness is enforced in the data model, not just in code (see [ADR 0005](docs/adr/0005-double-entry-ledger.md)).

**Double-entry journal.** Every movement is a *journal entry* of *postings* whose debits equal credits - checked by a
deferred Postgres trigger at commit, so even a hand-written SQL statement cannot save an unbalanced journal. The journal
is append-only (UPDATE/DELETE are rejected); a mistake is fixed by posting a reversing journal.

| Event | Postings |
|---|---|
| Opening deposit (money arrives from the bank) | Dr `EXTERNAL_FUNDING` · Cr customer |
| Transfer, debit leg | Dr sender (amount + fee) · Cr `SETTLEMENT` (amount) · Cr `FEE_INCOME` (fee) |
| Transfer, credit leg | Dr `SETTLEMENT` · Cr receiver |
| Compensation | the exact mirror of the debit journal - **the fee is refunded too** |
| Hold capture (money paid out) | Dr customer · Cr `EXTERNAL_FUNDING` |

`SETTLEMENT` is credited by the debit leg and debited by the credit leg, so it is exactly "money in flight" and returns to
zero when nothing is mid-transfer; a lingering balance is a stuck transfer. A unique index per `(transaction, step)`
makes it impossible to debit, credit or reverse the same transfer twice.

**Integrity audit.** `GET /api/v1/ledger/trial-balance` (ADMIN) - also run hourly - proves per currency that debits equal
credits, that no journal is unbalanced, that every account's cached balance equals the balance derived from its postings,
and that no transfer is stuck in settlement. Results are exported as metrics and alerts.

**Fees.** A configurable schedule charged to the sender on top of the transfer amount:
`fee = flat + amount × percent / 100`, clamped to `[minimum, maximum]`, rounded half-even to the currency's decimals
(`FEES_TRANSFER_FLAT`, `_PERCENT`, `_MINIMUM`, `_MAXIMUM`; all `0` = free, the default). The fee is its own statement line
and is booked to `FEE_INCOME`.

**Holds (authorizations).** `POST /api/v1/accounts/{id}/holds` (ADMIN) reserves part of a balance without moving it:
`available = balance - active holds`, and transfers can only spend the *available* amount. Capture a hold (fully or
partially) to post the real debit, release it, or let it lapse (default 7 days). Creation is idempotent per reference, and
holds take the same row lock as the transfer saga, so a hold and a transfer can never spend the same money.
`GET /api/v1/accounts/{id}/balance` returns `balance`, `heldAmount` and `availableBalance`.

**Reconciliation against the settlement bank.** Upload the bank's daily statement (`POST /api/v1/ledger/statements`, JSON or
CSV `reference,direction,amount,currency[,description]`); it is matched by reference against the ledger's
`EXTERNAL_FUNDING` movements for that UTC day. Every difference - missing on either side, amount, direction, currency or
duplicate reference - becomes an open *break* that an operator resolves with a note. A statement can only be uploaded once
per (source, date, currency). See the [runbook](docs/runbook.md#external-reconciliation-breaks).

**Funding.** In a real environment customers open accounts empty and are funded by an incoming payment; only an
**ADMIN** may record an opening deposit (with the bank's `fundingReference`). docker-compose sets
`ACCOUNT_SELF_SERVICE_FUNDING=true` so the demo and the e2e test can fund their own accounts - leave it `false` anywhere real.

### Idempotent transfers

`POST /api/v1/transactions/transfer` requires an **`Idempotency-Key`** header (1-100 chars, unique per logical
transfer, Stripe-style). The key is stored with the transaction under a **partial unique index on
`(requested_by, idempotency_key)`**, so the database - not just application code - guarantees a transfer is created
once, even when two retries race on different replicas.

| Situation | Result |
|---|---|
| First request with a key | `202 Accepted`, saga starts |
| Retry: same key, same body | `202` with `Idempotent-Replayed: true` and the **original** transaction - no second debit |
| Same key, different body | `422 Unprocessable Entity` |
| Header missing / malformed | `400` |
| Two identical requests at the same instant | one wins the unique index; the loser answers from the winner's transaction |

A replay is answered *before* the ownership, velocity and step-up checks (they already passed the first time, and
re-running them could wrongly reject a legitimate retry). The body fingerprint ignores the step-up token, because a
retry legitimately carries a fresh one. The frontend generates one key per transfer attempt and keeps it across
retries and the step-up round-trip.

### Poison messages: retries and dead-letter topics

Every `@KafkaListener` service installs a `DefaultErrorHandler`: transient failures are retried with exponential
backoff (0.5 s → 4 s); a record that still fails - or is malformed, where retrying cannot help - is published to
**`<topic>.DLT`** with the exception in headers and the partition moves on (one bad message can't block the ones behind
it). Nothing is silently dropped, `neobank_kafka_dead_letters_total` counts them, and an alert fires on any. The
`kafka-init` compose job pre-creates every topic (3 partitions) and its DLT; replay steps are in
[docs/runbook.md](docs/runbook.md#dead-letter-topics).

### Housekeeping and multi-replica safety

- `outbox_events`: `SENT` rows are purged after 72 h (`DEAD` rows are kept as evidence).
- `processed_events` (the idempotent-consumer dedup table): purged after 14 days - deliberately longer than Kafka's
  7-day topic retention, so a redelivery can never outlive its dedup record.
- Scheduled jobs in account/transaction-service take a **ShedLock** lock so they run on one replica at a time.

### Role-based access control

`fraud-service` and `audit-service` now enforce `hasRole("ADMIN")` on every
endpoint — the JWT's `role` claim (set at token issuance in `auth-service`)
is mapped to a Spring Security authority via a custom
`JwtAuthenticationConverter` in each service's `SecurityConfig`. There is
**no built-in admin credential**: `AdminUserSeeder` creates the first admin only when
`ADMIN_BOOTSTRAP_EMAIL` / `ADMIN_BOOTSTRAP_PASSWORD` are provided (`bootstrap-env.sh` generates a random
password), through the normal encrypted-user path. It never re-applies the password on later starts.

### Auth hardening (RS256, cookies, lockout, rate limiting)

A few things changed from the original demo-simple version of auth, aimed at
matching how real banks handle sessions:

- **RS256 + JWKS, not a shared secret.** `auth-service` alone holds the
  private signing key; every other service (including the gateway) verifies
  tokens against the public key published at `/.well-known/jwks.json`
  (`RsaKeyProperties`, `JwksController`). Compromising any downstream service
  can no longer let an attacker forge tokens for the whole platform.
- **Tokens live in httpOnly, Secure, SameSite=Strict cookies**, never in
  `localStorage` or a JSON response body (`AuthController`,
  `frontend/src/context/AuthContext.tsx`). This closes the classic "XSS
  steals your session token" hole. The gateway bridges the cookie to a
  standard `Authorization: Bearer` header internally
  (`CookieToAuthorizationHeaderFilter`) so downstream services don't need to
  know cookies exist at all.
- **Per-account lockout** after 5 consecutive failed logins, 15 minutes
  (`User.registerFailedLogin`, configurable via `security.lockout.*`).
- **Per-IP rate limiting** on `/api/v1/auth/**` at the gateway, Redis-backed
  so it's consistent across replicas (`RateLimiterConfig`,
  `clientIpKeyResolver`), plus a coarser Resilience4j limiter inside
  auth-service itself as a backstop.
- **Strict CORS allow-list** (`CORS_ALLOWED_ORIGINS`) instead of a wildcard
  origin — the real frontend never actually crosses origins (nginx/Vite both
  proxy `/api` server-side), so this is defense-in-depth for the case the
  gateway is ever reachable directly.

What this deliberately does **not** cover yet (tracked as follow-up work, not
oversights): OTP/step-up auth for login or high-value transfers, per-transfer
velocity limits, WebSocket-specific auth hardening beyond the cookie carrying
over on the handshake, PII encryption at rest, device/session binding, and
mTLS between services. None of those are safe to fake — they need to be built
deliberately, and some (mTLS, HSM-backed key custody) need real infrastructure
this repo can't provide on its own.

### Object-level authorization, MFA, and velocity limits

A second round of hardening, on top of the auth changes above:

- **Object-level authorization (IDOR fix).** Every account/transaction
  endpoint used to trust whatever id was in the request path/query - any
  authenticated customer could view or transfer out of ANY account by
  supplying its UUID, since authentication alone was mistaken for
  authorization. `AccountController` now checks the caller's JWT `sub`
  against `Account.ownerId` on every read; `TransactionController` and
  `TransferService` verify ownership synchronously against account-service
  (`AccountClient`, forwarding the caller's own token) before ever starting a
  transfer, and a transaction can only be viewed by a party to it.
- **TOTP-based MFA** (`TotpService`, RFC 6238, JDK crypto only - no external
  MFA library) for two things: (1) an optional second factor at login
  (`AuthService.login` returns a `MfaRequired` challenge instead of tokens
  when enabled; completed via `POST /auth/mfa/login-challenge`), and (2)
  mandatory step-up verification for any transfer at or above
  `transaction.step-up.threshold-amount` (`POST /auth/mfa/step-up` issues a
  short-lived, single-use token that `StepUpTokenValidator` checks for
  purpose, subject, and replay before transaction-service will proceed).
- **Velocity limits** (`VelocityLimitService`): a rolling 24h cap on money
  sent out of one account, and a cap on transfer attempts per hour -
  independent of the step-up threshold, since "many small transfers fast" and
  "one very large transfer" are different attack shapes.

Still open after this round: WebSocket-specific auth hardening beyond the
cookie carrying over on the handshake, PII encryption at rest, device/session
binding, and mTLS between services - same reasoning as above, these need
deliberate work and (for mTLS/HSM key custody) real infrastructure.

### Double-entry ledger

`account-service` now writes an append-only `ledger_entries` row every time
`Account.balance` changes (`LedgerEntry`, `LedgerEntryRepository`) - opening
deposits, transfer debits/credits, and compensating reversals. `balance`
stays a fast-read cached column (mutated directly, same as before), but the
ledger is the actual source of truth for "what happened and when": each
entry records its type (DEBIT/CREDIT), amount, the resulting balance, and the
transaction id it belongs to, and is never edited or deleted after being
written.

The balance mutation and its ledger entry are written in the same local DB
transaction, so they can't drift from a partial write - but "should never
drift" is exactly the kind of assumption a bank shouldn't trust blindly.
`LedgerReconciliationService` recomputes each account's balance purely from
its ledger entries and compares it to the cached value, both on demand
(`GET /accounts/{id}/reconcile`) and on a periodic sweep
(`ledger.reconciliation.interval-ms`, default 5 min) that logs any mismatch -
in a real deployment that log line would be an alert/incident instead.
`GET /accounts/{id}/ledger` exposes the paginated entry history itself as an
audit trail, separate from transaction-service's saga-status view of the
same transfers.

The sweep is guarded by **ShedLock** (a lock row in the `shedlock` table), so with N account-service replicas it runs
on exactly one of them per interval, and any drift increments `neobank_ledger_reconciliation_drift_total`
(alerting rule included). Known limitation: it still does a full account-table scan - fine at demo scale; a larger
deployment would paginate or shard it.

### PII encryption, device binding

- **PII encrypted at rest.** `email` and `full_name` on `users` are AES-256-
  GCM encrypted column-by-column (`EncryptedStringConverter`, applied
  transparently via `@Convert` - the rest of the codebase reads/writes plain
  Strings). Each encryption uses a fresh random IV, so the same plaintext
  never produces the same ciphertext twice - which also means the column can
  no longer be searched by equality. Login/registration instead look up a
  separate deterministic HMAC **blind index** (`User.emailHash`,
  `BlindIndexService`) computed from the normalized email - the standard
  pattern for "encrypted but still searchable by exact match."
  **Migration note:** this required replacing the old raw-SQL admin-user seed
  (a plaintext `INSERT` can't produce ciphertext or a blind-index hash) with
  `AdminUserSeeder`, an `ApplicationRunner` that seeds through the normal
  entity path instead. If you already have a local database from before this
  change, run `docker compose down -v` to start fresh rather than layering
  this migration on top of the old plaintext row - Flyway will also flag a
  checksum mismatch on the old seed migration either way.
- **Account numbers masked in list views.** `GET /accounts?ownerId=...`
  (the accounts overview) returns masked numbers (`••••4821`,
  `Account.getMaskedAccountNumber()`); the full number is only ever returned
  from a request for that one specific account
  (`GET /accounts/{id}`/`by-number/{accountNumber}`) - the same
  show-on-demand-only pattern real banking apps use, rather than a full
  account number sitting on a screen that's easy to shoulder-surf or capture
  in a screen recording.
- **Device binding.** Refresh tokens are now bound to a `device_id` cookie
  (random, long-lived, httpOnly) issued alongside them. `RefreshTokenService`
  rejects (and burns) a refresh token presented with a different device_id
  than it was issued to, treating that as a possible stolen-token replay
  rather than a normal refresh. First-time devices are logged
  (`AuthService.issueTokens`) as a stand-in for a real "new device signed in"
  notification - wiring that up needs a user-keyed event + email lookup that
  notification-service doesn't currently have (it's keyed by account, not by
  user), which is a bigger, separate change from device binding itself.

### mTLS and Kafka transport security

Both need real infrastructure this repo can't provide on its own - certs,
a CA, and (for Kafka) SASL credentials - so this is a runbook plus config
templates, not a flip-a-switch change:

- **Service-to-service mTLS.** The straightforward path for a Spring Boot
  fleet this size is a service mesh sidecar (Istio, Linkerd) that handles
  mTLS transparently at the network layer with zero application code changes
  - worth strongly preferring over hand-rolled two-way SSL in every service's
    `application.yml`, which multiplies the amount of keystore/truststore
    rotation you have to manage by the number of services. If you do need it
    at the application layer, Spring Boot's `server.ssl.client-auth: need`
    plus a per-service keystore/truststore (issued by an internal CA -
    cert-manager if you're on k8s) is the mechanism; every `RestTemplate`/
    `WebClient` making internal calls (see `transaction-service`'s
    `AccountClient`) would also need its own truststore configured to trust
    that CA.
- **Kafka SASL_SSL.** Local docker-compose intentionally runs `PLAINTEXT`
  (no TLS/auth) - fine for a laptop, not for anything else. For a real
  deployment: generate broker + client certs, switch the broker's advertised
  listener to `SASL_SSL`, and set on every producer/consumer:
  ```yaml
  spring:
    kafka:
      security:
        protocol: SASL_SSL
      properties:
        sasl.mechanism: SCRAM-SHA-512
        sasl.jaas.config: >
          org.apache.kafka.common.security.scram.ScramLoginModule required
          username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";
        ssl.truststore.location: /path/to/kafka.truststore.jks
        ssl.truststore.password: ${KAFKA_TRUSTSTORE_PASSWORD}
  ```
  Managed Kafka (Confluent Cloud, MSK, Aiven) typically hands you these
  values directly rather than requiring you to run your own CA.

---
