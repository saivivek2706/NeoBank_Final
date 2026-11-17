# NeoBank on Kubernetes

Manifests are numbered to apply in a sensible order (infra before the
services that depend on it), though `kubectl apply -f k8s/` applies them all
correctly regardless of order since Kubernetes retries pending pods until
their dependencies (Services, ConfigMaps) exist.

## Prerequisites

- A local cluster: [kind](https://kind.sigs.k8s.io/) or
  [minikube](https://minikube.sigs.k8s.io/) both work fine for this.
- The 9 application images plus the frontend built locally (`neobank/eureka-server:latest`,
  `neobank/config-server:latest`, `neobank/api-gateway:latest`,
  `neobank/auth-service:latest`, `neobank/account-service:latest`,
  `neobank/transaction-service:latest`, `neobank/notification-service:latest`,
  `neobank/fraud-service:latest`, `neobank/audit-service:latest`, `neobank/compliance-service:latest`,
  `neobank/frontend:latest`) - the same Dockerfiles used by
  `docker-compose.yml` work here unchanged:

```bash
# from the repo root
for svc in eureka-server config-server api-gateway auth-service account-service \
           transaction-service notification-service fraud-service audit-service compliance-service; do
  docker build -f $svc/Dockerfile -t neobank/$svc:latest .
done
docker build -f frontend/Dockerfile -t neobank/frontend:latest frontend
```

- Load the images into your cluster (kind doesn't pull from your local Docker
  daemon automatically the way Docker Compose does):

```bash
for img in eureka-server config-server api-gateway auth-service account-service \
           transaction-service notification-service fraud-service audit-service compliance-service frontend; do
  kind load docker-image neobank/$img:latest
done
```
(For minikube, use `minikube image load neobank/$img:latest` instead, or
point your shell's Docker daemon at minikube's with `eval $(minikube -p minikube docker-env)`
before running the build loop above.)

## Deploy

The `neobank-secrets` Secret is **not in the repo**. Generate fresh secrets and create it first:

```bash
./scripts/bootstrap-env.sh      # writes .env (RSA keypair, AES/HMAC keys, DB passwords, first admin)
./scripts/k8s-secrets.sh        # kubectl create secret generic neobank-secrets ... from .env
kubectl apply -f k8s/
```

Watch everything come up:
```bash
kubectl -n neobank get pods -w
```

Infra pods (`postgres-0`, `mongo-0`, `kafka-0`, `redis`) need to be `Running`
and `Ready` before the application pods will pass their readiness probes and
start receiving traffic - this is expected and typically takes 1-2 minutes
on first boot.

## Access it

```bash
kubectl -n neobank get svc api-gateway frontend
```
Both are `type: LoadBalancer`. On kind/minikube without a real cloud load
balancer, use:
```bash
kubectl -n neobank port-forward svc/frontend 3000:80
kubectl -n neobank port-forward svc/api-gateway 8000:8000
```
Then open http://localhost:3000, same as the Docker Compose setup.

## Important: cookies require HTTPS (or literal "localhost")

Login sets `Secure` cookies (httpOnly, SameSite=Strict - see auth-service's
`AuthController`). Browsers will silently refuse to store a `Secure` cookie
over plain HTTP **unless the host is literally `localhost`**, which is a
built-in browser exception.

- **`kubectl port-forward` to `localhost:3000`** (above) - works out of the
  box, no extra setup, because `localhost` gets that exception.
- **The Ingress path (`k8s/50-ingress.yaml`, host `neobank.local`)** - will
  NOT work out of the box. Without TLS, the browser drops the login/refresh
  cookies silently: login appears to succeed (200 response) but you're
  immediately logged out on the next request/page load, with no error
  message pointing at why. Either stick to port-forward for local testing,
  or terminate TLS in front of the Ingress (e.g. `cert-manager` + a
  self-signed or `mkcert` certificate for `neobank.local`, with an
  `/etc/hosts` entry pointing it at your ingress controller's address) before
  relying on that path.

## What's deliberately simplified here (and why)

- **Kafka is a single broker** (`k8s/13-kafka.yaml`). A real production
  cluster would use the [Strimzi Operator](https://strimzi.io/) for a
  proper multi-broker, replicated setup with automated rolling upgrades -
  that's a whole additional CRD-based system, out of scope for a portfolio
  deployment, but worth naming as the production path in an interview.
- **The Secret is created from a local `.env` by `scripts/k8s-secrets.sh`**
  (nothing sensitive is committed). A real deployment would source these from
  a managed secret store (AWS Secrets Manager, HashiCorp Vault, or the
  External Secrets Operator) and never pass them through a developer machine.
- **Containers run as a non-root user** (see the Dockerfiles) and the frontend
  uses `nginx-unprivileged` on 8080, but the pod specs don't yet set
  `securityContext` (`readOnlyRootFilesystem`, dropped capabilities) or
  resource-limit tuning beyond the basics.
- **No NetworkPolicies** restricting which pods can talk to which - in
  production you'd lock down, e.g., only `account-service` and
  `fraud-service` being allowed to reach `redis`, not every pod in the
  namespace.
- **StatefulSets for Postgres/Mongo/Kafka use a single replica** - fine for
  a demo, but production would run replicated StatefulSets with proper
  backup/restore (e.g. via Velero) rather than relying on the PVC alone.
