#!/usr/bin/env bash
# Creates/updates the `neobank-secrets` Secret in the `neobank` namespace from the local .env
# (generate that first with scripts/bootstrap-env.sh). For demo/local clusters; in a real cluster
# source these values from Vault / External Secrets / Sealed Secrets instead.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo "No .env - run scripts/bootstrap-env.sh first"; exit 1; }

kubectl create namespace neobank --dry-run=client -o yaml | kubectl apply -f -
args=()
for key in POSTGRES_USER POSTGRES_PASSWORD MONGO_USER MONGO_PASSWORD JWT_KEY_ID JWT_PRIVATE_KEY_B64 \
           JWT_PUBLIC_KEY_B64 PII_ENCRYPTION_KEY_B64 PII_BLIND_INDEX_KEY_B64 ADMIN_BOOTSTRAP_EMAIL ADMIN_BOOTSTRAP_PASSWORD; do
  value="$(grep -E "^${key}=" .env | head -1 | cut -d= -f2-)"
  [[ -n "$value" ]] || { echo "Missing $key in .env"; exit 1; }
  args+=(--from-literal="${key}=${value}")
done
kubectl -n neobank create secret generic neobank-secrets "${args[@]}" --dry-run=client -o yaml | kubectl apply -f -
echo "neobank-secrets applied."
