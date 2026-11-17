// k6 load test for the transfer saga, through the API gateway.
//
//   k6 run -e BASE_URL=http://localhost:8000 load-tests/transfer-load.js
//   k6 run -e VUS=20 -e DURATION=2m load-tests/transfer-load.js
//
// Each virtual user registers its own customer with two accounts, then repeatedly transfers small amounts back
// and forth with a fresh Idempotency-Key per transfer, and polls until the saga reaches a terminal state.
// What it measures: HTTP latency of the 202 acknowledgement, and the END-TO-END saga latency (POST -> COMPLETED)
// across Kafka, which is the number that actually matters for a payment.
//
// NOTE: the gateway rate-limits /auth (10/s burst per client IP) and each account is capped by the velocity
// rules (default 20 attempts/hour per account). Raise VELOCITY_MAX_ATTEMPTS_HOUR on transaction-service for
// sustained load runs, and keep VUS modest because every VU registers once.
import http from "k6/http";
import { check, sleep, fail } from "k6";
import { Trend, Counter } from "k6/metrics";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE = __ENV.BASE_URL || "http://localhost:8000";
const sagaDuration = new Trend("saga_duration_ms", true);
const sagaCompleted = new Counter("saga_completed");
const sagaFailed = new Counter("saga_failed");
const sagaTimeout = new Counter("saga_timeout");

export const options = {
  scenarios: {
    transfers: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || "1m",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{name:transfer}": ["p(95)<500"],
    saga_duration_ms: ["p(95)<3000"],
    saga_timeout: ["count==0"],
  },
};

const json = { "Content-Type": "application/json" };

export function setup() {
  return {};
}

// k6 runs this once per VU iteration; per-VU state lives in module scope (each VU has its own copy).
let session = null;

function bootstrap() {
  const email = `k6-${uuidv4()}@example.com`;
  const reg = http.post(`${BASE}/api/v1/auth/register`,
    JSON.stringify({ email, password: "Sup3r-secret-pw", fullName: "k6 user" }), { headers: json });
  if (reg.status !== 201) fail(`register failed: ${reg.status} ${reg.body}`);
  const token = reg.cookies["access_token"][0].value;
  const userId = reg.json("userId");
  const auth = { ...json, Authorization: `Bearer ${token}` };

  const open = (balance) => {
    const r = http.post(`${BASE}/api/v1/accounts`, JSON.stringify({
      ownerId: userId, ownerName: "k6 user", accountType: "SAVINGS", currency: "SGD", openingBalance: balance,
    }), { headers: auth });
    if (r.status !== 201) fail(`open account failed: ${r.status} ${r.body}`);
    return r.json("id");
  };
  return { auth, a: open(100000), b: open(100000) };
}

export default function () {
  if (!session) session = bootstrap();

  // alternate direction so balances stay bounded
  const [from, to] = Math.random() < 0.5 ? [session.a, session.b] : [session.b, session.a];
  const started = Date.now();

  const res = http.post(`${BASE}/api/v1/transactions/transfer`,
    JSON.stringify({ fromAccountId: from, toAccountId: to, amount: 1.0, currency: "SGD" }),
    { headers: { ...session.auth, "Idempotency-Key": uuidv4() }, tags: { name: "transfer" } });
  if (!check(res, { "transfer accepted (202)": (r) => r.status === 202 })) return;

  const txId = res.json("id");
  for (let i = 0; i < 60; i++) {
    const s = http.get(`${BASE}/api/v1/transactions/${txId}`, { headers: session.auth, tags: { name: "poll" } });
    const status = s.json("status");
    if (status === "COMPLETED") { sagaCompleted.add(1); sagaDuration.add(Date.now() - started); return; }
    if (status === "FAILED") { sagaFailed.add(1); sagaDuration.add(Date.now() - started); return; }
    sleep(0.1);
  }
  sagaTimeout.add(1);
}
