# Security policy

NeoBank is a portfolio / reference project and moves no real money, but it is built to production security
practices and is treated that way.

## Reporting a vulnerability

Please **do not open a public issue**. Report it privately through GitHub's *Security → Report a vulnerability*
form on this repository. Include steps to reproduce and the affected commit. You can expect an acknowledgement
within a few days.

## What is in scope

Authentication and session handling (RS256 JWT, refresh rotation, MFA, device binding), authorization
(object-level checks), PII encryption at rest, the transfer saga's integrity (idempotency, compensation,
ledger reconciliation) and the container / Kubernetes configuration.

## Secrets

No secret is committed to this repository. `scripts/bootstrap-env.sh` generates a fresh `.env` (RSA signing
keypair, AES / HMAC keys, database passwords, the first admin credential) for each checkout; `.env` is
git-ignored. If you find a credential in the history or the tree, treat it as a vulnerability and report it.
