# API

Interactive docs: every service serves Swagger UI at `/swagger-ui.html` and the OpenAPI document at `/v3/api-docs`;
the gateway aggregates all of them at **http://localhost:8000/swagger-ui.html**.

All client calls go through the gateway (`:8000`), authenticated with the `access_token` httpOnly cookie or
`Authorization: Bearer <jwt>`.

## Transfers

```
POST /api/v1/transactions/transfer
Idempotency-Key: <unique per logical transfer, 1-100 chars>   (required)
{ "fromAccountId": "...", "toAccountId": "...", "amount": 250, "currency": "SGD", "stepUpToken": "..."? }
```

| Status | Meaning |
|---|---|
| 202 | Accepted, saga running (`Idempotent-Replayed: true` if this was a retry of the same request) |
| 400 | Validation error, missing/malformed `Idempotency-Key` |
| 403 | Caller doesn't own `fromAccountId` |
| 422 | `Idempotency-Key` already used for a different transfer |
| 428 | Step-up (MFA) required for this amount |
| 429 | Velocity limit exceeded |
| 503 | Circuit breaker open (downstream unavailable) |

`GET /api/v1/transactions/{id}` returns the status (`DEBIT_PENDING`, `CREDIT_PENDING`, `COMPENSATING`, `COMPLETED`, `FAILED`);
or subscribe over WebSocket to `/topic/transactions/{id}`.

Every response carries `X-Request-Id` - quote it when reporting a problem.

## Accounts, holds and the ledger

| Endpoint | Who | Purpose |
|---|---|---|
| `GET /api/v1/accounts/{id}/balance` | owner / ADMIN | `balance`, `heldAmount`, `availableBalance` |
| `POST /api/v1/accounts/{id}/holds` | ADMIN | Reserve funds. Body `{amount, reference, reason?, ttlMinutes?}`; idempotent per `reference` |
| `GET /api/v1/accounts/{id}/holds` | owner / ADMIN | Active holds |
| `POST /api/v1/accounts/{id}/holds/{holdId}/release` | ADMIN | Free the reservation |
| `POST /api/v1/accounts/{id}/holds/{holdId}/capture` | ADMIN | Post the real debit; optional `{amount}` for a partial capture |
| `GET /api/v1/ledger/trial-balance` | ADMIN | Trial balance and integrity checks (`healthy`) |
| `POST /api/v1/ledger/statements` | ADMIN | Upload a bank statement (JSON, or `text/csv` with `?source=&statementDate=&currency=`) and reconcile it |
| `GET /api/v1/ledger/reconciliation-runs[/{id}]` | ADMIN | Runs and their breaks |
| `POST /api/v1/ledger/reconciliation-breaks/{id}/resolve` | ADMIN | Close a break with `{note}` |

Opening an account with a non-zero `openingBalance` is ADMIN-only unless `ACCOUNT_SELF_SERVICE_FUNDING=true` (demo);
supply the bank's `fundingReference` so the deposit can be matched on the bank statement.

## Compliance (ADMIN only)

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/compliance/cases?status=&assignee=&page=&size=` | Case queue (open cases by default, most urgent first) |
| `GET /api/v1/compliance/cases/{id}` | A case with its alerts and full history |
| `POST /api/v1/compliance/cases/{id}/assign` | Take it (body `{"assignee": "<uuid>"}` optional; defaults to you) |
| `POST /api/v1/compliance/cases/{id}/notes` | Investigation note |
| `POST /api/v1/compliance/cases/{id}/escalate` | Ask for a second pair of eyes (note required) |
| `POST /api/v1/compliance/cases/{id}/close` | `{"disposition": "NO_SUSPICION|FALSE_POSITIVE|SAR_FILED|ACCOUNT_RESTRICTED", "note": "..."}`; `SAR_FILED` and clearing a frozen sanctions hit need an escalated case and a different closer (409) |
| `POST /api/v1/compliance/watchlist/import?mode=FULL|DELTA` | Body `text/csv`: `list_source,external_id,name,aliases(\|-separated),country,entity_type`; re-screens every customer |
| `GET /api/v1/compliance/watchlist/versions`, `/watchlist/entries?query=` | Imports and entries |
| `POST /api/v1/compliance/screening/name` | `{"name": "..."}` - screen a name without storing anything |
| `GET /api/v1/compliance/screening/parties?status=`, `/parties/{accountId}` | Screened customers (default: awaiting a decision) |
| `GET /api/v1/compliance/overview` | Open/overdue cases, parties awaiting review, alert volume |

## Audit trail (ADMIN only)

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/audit/chain/verify?recent=N` | Recompute the hash chain (whole chain, or the newest N records); `valid:false` names the first broken record and why |
| `GET /api/v1/audit/chain/head` | Current head (sequence + hash): copy it to storage outside the database |
| `GET /api/v1/audit/chain/checkpoints`, `POST /api/v1/audit/chain/checkpoint` | Recorded checkpoints / record one now |
