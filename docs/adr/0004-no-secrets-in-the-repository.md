# ADR 0004: No secrets in the repository

**Status:** accepted

## Context
The project originally shipped a development keypair, AES key, database passwords and an admin password as defaults.
Anyone could read them in the repo, which makes "encrypted at rest" and "signed tokens" meaningless for any shared
deployment, and sets a bad precedent.

## Decision
- Services have **no default** for passwords or keys and fail fast when they are missing.
- `scripts/bootstrap-env.sh` generates a fresh `.env` (git-ignored) per checkout; docker-compose requires its values.
- The first admin is created only from `ADMIN_BOOTSTRAP_*`, never from a built-in credential.
- Kubernetes gets its Secret from `scripts/k8s-secrets.sh`; production should use a secrets manager / External Secrets.
- Unit tests use a throwaway key through a system property, never the production code path defaults.
- Trivy secret scanning runs in CI.

## Consequences
+ A fresh clone is safe by default; every environment has unique keys.
- One extra setup step (`make up` does it). Re-generating keys invalidates data encrypted with the old ones.
