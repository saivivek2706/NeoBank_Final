# Contributing

## Setup

```bash
./scripts/bootstrap-env.sh        # generates .env with fresh random secrets (git-ignored)
docker compose up --build         # full stack
mvn verify                        # lint + unit + Testcontainers integration tests (needs Docker)
cd frontend && npm ci && npm run lint && npm run build
```

`make help` lists the shortcuts.

## Workflow

1. Branch from `main`, keep PRs focused.
2. `mvn verify` must pass - it runs **checkstyle** (validate phase), all tests and JaCoCo.
3. Frontend changes: `npm run lint` and `npm run build`.
4. Touching the saga, Kafka topics or service wiring? Add the `e2e` label to the PR so the full-stack
   Testcontainers test runs (`mvn -Pe2e verify -pl e2e-tests` locally).
5. Fill in the PR template.

## Conventions

- **Database**: schema changes are a *new* Flyway migration (`V<next>__description.sql`). Never edit a migration that has run.
- **Kafka consumers** must be idempotent (record the event id in `processed_events` in the same transaction as the side effect) and rely on the shared dead-letter handler - don't swallow exceptions.
- **Publishing events**: write to the outbox table in the same transaction as the state change; never call Kafka from inside a business transaction.
- **Money**: `BigDecimal`/`NUMERIC` only, never floating point.
- **Config & secrets**: every new variable goes in `.env.example`, `docker-compose.yml` and the k8s ConfigMap/Secret script. No default passwords or keys in code or YAML.
- **Architecture decisions** worth remembering go in `docs/adr/`.
- Commit messages: imperative mood, explain *why*.
