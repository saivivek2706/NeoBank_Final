# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added (compliance)
- **`compliance-service`**: sanctions screening at onboarding and on every watchlist import (exact name -> automatic account freeze
  and a CRITICAL case; fuzzy -> human review, capped below the auto-freeze tier), AML transaction monitoring (large transaction,
  structuring, rapid movement, high velocity), and case management with SLA due dates, an append-only history and
  **maker-checker (four-eyes)** approval for SAR filing and for clearing a frozen sanctions hit.
- Watchlist import (CSV, versioned with a checksum, FULL/DELTA modes) and append-only screening evidence.
- Account service publishes `account.opened` and obeys `compliance.account.action` (freeze / unfreeze).
- **Hash-chained audit trail**: gapless sequence, SHA-256 chaining, checkpoints, verification API
  (`/api/v1/audit/chain/verify|head|checkpoints`), scheduled verification metric + alert; account openings and compliance
  commands are audited too.
- Alerts, dashboard panels, runbook sections and ADR 0006 for all of the above.

### Fixed
- Unit tests built a plain `ObjectMapper` and serialised records containing `Instant`, which Jackson refuses without the JavaTime
  module; they now register it (production uses Spring's mapper, which already did).

### Added (ledger & money correctness)
- **Double-entry journal** (`journal_entries` + `postings`, migration V4): every movement balances, enforced by a
  deferred database trigger; append-only; one journal per transfer step; system accounts `SETTLEMENT`, `FEE_INCOME`,
  `EXTERNAL_FUNDING`. Existing history is backfilled into balanced journals against `LEGACY_CLEARING`.
- **Ledger integrity audit** and `GET /api/v1/ledger/trial-balance`: trial balance per currency, unbalanced journals,
  account drift, transfers stuck in settlement; hourly job, metrics and alerts.
- **Configurable transfer fees** (charged to the sender, booked to `FEE_INCOME`, refunded on compensation).
- **Authorization holds** (`available = balance - active holds`; place / release / capture, partial capture, expiry).
- **External reconciliation**: upload the settlement bank's statement (JSON or CSV), match against the ledger by
  reference, track breaks to resolution with a note.
- Opening balances require an ADMIN unless `ACCOUNT_SELF_SERVICE_FUNDING` (demo flag, on in docker-compose) is set.

### Added
- **Idempotent transfers**: `POST /transfer` requires an `Idempotency-Key` header. Retries return the original
  transaction (`Idempotent-Replayed: true`); the same key with a different body returns `422`. Enforced by a
  partial unique index (migration `V2`), safe under concurrent retries.
- **Kafka dead-letter handling** in every consuming service: exponential-backoff retries, then the record is parked
  on `<topic>.DLT` instead of being dropped. `kafka-init` pre-creates topics and DLTs.
- **Saga & outbox metrics** (`neobank_transfers_*`, `neobank_outbox_*`, `neobank_kafka_dead_letters_total`,
  ledger-reconciliation drift), a *Transfer Saga* Grafana dashboard and Prometheus alert rules.
- **ShedLock** so scheduled jobs (ledger reconciliation, cleanup) run on one replica at a time.
- Housekeeping jobs: outbox `SENT` rows and `processed_events` are purged after a retention window.
- **Request correlation**: gateway stamps `X-Request-Id`, every service logs it; structured JSON (ECS) logging in compose.
- **OpenAPI / Swagger UI** on every service and an aggregated UI on the gateway (`/swagger-ui.html`).
- Tests: Testcontainers integration tests (transaction-service Postgres, audit-service MongoDB), unit tests for
  gateway, notification-service, eureka-server and config-server, and a full-stack e2e saga test.
- CI: checkstyle, JaCoCo coverage report, ESLint, Trivy scans, e2e job. Dependabot, k6 load test, Makefile.
- Docs: architecture diagrams, runbook, ADRs. `LICENSE`, `SECURITY.md`, `CONTRIBUTING.md`, PR/issue templates.

### Changed
- **Secrets are no longer committed.** `scripts/bootstrap-env.sh` generates `.env` (JWT keypair, AES/HMAC keys, DB
  passwords, first admin); services have no default passwords or keys; the admin account is seeded only from
  `ADMIN_BOOTSTRAP_*`; the k8s Secret is created by `scripts/k8s-secrets.sh`.
- Docker images run as a non-root user, have `HEALTHCHECK`s and container-aware JVM flags; compose waits on health
  checks; the frontend image is `nginx-unprivileged` on port 8080 (host port 3000 unchanged).
- docker-compose: removed obsolete `version`, pinned Kafka UI, Grafana/Prometheus/Kafka UI behind the
  `observability` profile, infrastructure ports bound to localhost.

### Fixed
- Compensation now refunds exactly what the debit took, fee included, by mirroring the original journal.
- `/actuator/prometheus` returned 404 in every service (missing `micrometer-registry-prometheus`), leaving Grafana empty.
- Audit unique index on `eventId` was never created (Spring Data MongoDB does not auto-create indexes by default).
- A credit (or debit) to a non-existent account threw inside the Kafka listener, stranding the transfer with the
  sender already debited. It now fails the saga step so the debit is compensated.
- Removed unused imports; gateway had a duplicated dependency declaration.
