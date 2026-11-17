# Load tests (k6)

```bash
brew install k6            # or see https://k6.io/docs/get-started/installation/
docker compose up --build -d
k6 run -e VUS=5 -e DURATION=1m load-tests/transfer-load.js
```

The script registers a customer per virtual user, opens two accounts, and keeps transferring between them with a
fresh `Idempotency-Key` each time, polling until the saga finishes. It reports the 202 latency and the **end-to-end
saga latency** (`saga_duration_ms`), and fails the run if p95 acknowledgement > 500 ms, p95 saga > 3 s, any saga times
out, or more than 1% of HTTP requests fail.

Watch the *NeoBank - Transfer Saga* dashboard in Grafana (http://localhost:3001) while it runs.

## Results

Fill this in from **your own machine** - numbers depend entirely on hardware, so none are published here.

| Date | Machine | VUS | Duration | Transfers/s | Ack p95 | Saga p95 | Failed | Notes |
|------|---------|-----|----------|-------------|---------|----------|--------|-------|
|      |         |     |          |             |         |          |        |       |

Raise `VELOCITY_MAX_ATTEMPTS_HOUR` on `transaction-service` before sustained runs, otherwise the per-account
velocity limit (default 20 attempts/hour) will - correctly - start rejecting transfers.
