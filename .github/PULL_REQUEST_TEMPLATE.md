## What & why

<!-- What does this change, and why is it needed? Link the issue if there is one. -->

## How it was tested

- [ ] Unit tests added/updated
- [ ] Integration tests (Testcontainers) added/updated where a database or Kafka is involved
- [ ] `mvn verify` passes locally (includes checkstyle)
- [ ] Frontend: `npm run lint && npm run build` pass (if the UI changed)
- [ ] Added the `e2e` label if this touches the saga, Kafka topics or service wiring

## Checklist

- [ ] No secrets, keys or real credentials committed (`.env` is git-ignored)
- [ ] New config has an entry in `.env.example` and the docker-compose / k8s manifests
- [ ] DB changes are a **new** Flyway migration - never edit an applied one
- [ ] New Kafka consumers are idempotent (processed_events) and covered by the dead-letter handler
- [ ] Docs / ADR / CHANGELOG updated if behaviour or architecture changed
