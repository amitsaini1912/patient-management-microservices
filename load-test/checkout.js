// Day 22: k6 load test of the whole checkout path, the way a patient pays:
//   1. billing account for a new patient   gRPC CreateBillingAccount -> billing :9001 (what patient-service does)
//   2. tokenize a NEW card                 POST /api/cards           -> gateway -> auth -> card-vault
//   3. authorize                           POST /api/payments        -> gateway -> auth -> billing -> card-vault,
//                                                                       risk engine, simulator (network + issuer) -> vault
//   4. capture (if AUTHORIZED)             POST /api/payments/{id}/capture -> ... -> simulator
// Every iteration uses a new patient + new card, like real traffic: reusing a few would trip the risk engine's
// velocity rules (4th payment on a card in 1 h -> held for review) and billing's per-card limit, and we'd measure those.
//
// Open model (arrival rate): k6 STARTS iterations at a fixed rate whether or not earlier ones finished, so a slow
// system shows up as latency + dropped iterations instead of quietly lowering the load (coordinated omission).
//
//   k6 run -e PROFILE=smoke load-test/checkout.js
//   k6 run -e PROFILE=step  --summary-export=load-test/results/step-before.json load-test/checkout.js
//   k6 run -e PROFILE=rate -e RATE=20 -e DURATION=2m --summary-export=load-test/results/rate20-before.json load-test/checkout.js
import http from 'k6/http';
import grpc from 'k6/net/grpc';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:4004';
const BILLING_GRPC = __ENV.BILLING_GRPC || 'localhost:9001';
const PROFILE = __ENV.PROFILE || 'smoke';

const profiles = {
  // correctness first: 10 checkouts one after another, every one must work
  smoke: { executor: 'shared-iterations', vus: 1, iterations: 10, maxDuration: '2m' },
  // find the knee: +5 checkouts/s every minute, 5 -> 40/s
  step: {
    executor: 'ramping-arrival-rate', startRate: 5, timeUnit: '1s',
    preAllocatedVUs: 50, maxVUs: 400,
    stages: [
      { duration: '30s', target: 5 }, { duration: '1m', target: 5 },
      { duration: '10s', target: 10 }, { duration: '1m', target: 10 },
      { duration: '10s', target: 15 }, { duration: '1m', target: 15 },
      { duration: '10s', target: 20 }, { duration: '1m', target: 20 },
      { duration: '10s', target: 30 }, { duration: '1m', target: 30 },
      { duration: '10s', target: 40 }, { duration: '1m', target: 40 },
    ],
  },
  // one fixed rate, for before/after comparisons
  rate: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 10), timeUnit: '1s',
    duration: __ENV.DURATION || '2m', preAllocatedVUs: 50, maxVUs: 400,
  },
};
if (!profiles[PROFILE]) throw new Error(`PROFILE must be one of ${Object.keys(profiles)}`);

export const options = {
  scenarios: { checkout: profiles[PROFILE] },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    // the targets we report against (not hard limits of the design): <1% errors, p95 of a whole checkout < 2 s
    http_req_failed: ['rate<0.01'],
    checkout_duration: ['p(95)<2000'],
    'http_req_duration{name:authorize}': ['p(95)<1000'],
    'http_req_duration{name:capture}': ['p(95)<1000'],
    'http_req_duration{name:tokenize}': ['p(95)<500'],
    // k6 had no free VU to start an iteration on time = the system can't keep up with this rate
    dropped_iterations: ['count<1'],
  },
};

const checkoutDuration = new Trend('checkout_duration', true);   // steps 2-4, as the patient waits for them
const accountDuration = new Trend('grpc_account_duration', true);
const outcomes = new Counter('payment_outcomes');               // tagged with the payment's status
const grpcClient = new grpc.Client();
grpcClient.load(['../billing-service/src/main/proto'], 'billing_service.proto');
let grpcConnected = false;

export function setup() {
  const res = http.post(`${GATEWAY}/auth/login`,
    JSON.stringify({ email: __ENV.LOGIN_EMAIL || 'testuser@test.com', password: __ENV.LOGIN_PASSWORD || 'password123' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  if (res.status !== 200) fail(`login failed: ${res.status} ${res.body}`);
  return { token: res.json('token') };
}

export default function (data) {
  if (!grpcConnected) {
    grpcClient.connect(BILLING_GRPC, { plaintext: true, timeout: '5s' });
    grpcConnected = true;
  }
  const auth = { Authorization: `Bearer ${data.token}`, 'Content-Type': 'application/json' };

  // 1. account for a new patient
  let t = Date.now();
  const acc = grpcClient.invoke('BillingService/CreateBillingAccount', {
    patientId: uuid(), name: 'Load Test', email: `load+${exec.scenario.iterationInTest}@test.com`,
  }, { tags: { name: 'create_account' } });
  accountDuration.add(Date.now() - t);
  if (!check(acc, { 'account created': (r) => r && r.status === grpc.StatusOK })) return;
  const accountId = acc.message.accountId;

  const start = Date.now();
  // 2. new card -> token
  const card = http.post(`${GATEWAY}/api/cards`,
    JSON.stringify({ pan: randomPan(), expiry_month: 12, expiry_year: 2030 }),
    { headers: auth, tags: { name: 'tokenize' } });
  if (!check(card, { 'card tokenized (201)': (r) => r.status === 201 })) return;

  // 3. authorize INR 100.00 - 2,000.00 (small: the risk engine holds new cards from INR 50,000)
  const amount = (100 + Math.floor(Math.random() * 1900)).toFixed(2);
  const pay = http.post(`${GATEWAY}/api/payments`,
    JSON.stringify({ billingAccountId: accountId, amount, currency: 'INR', paymentMethodToken: card.json('token') }),
    { headers: { ...auth, 'Idempotency-Key': uuid(), 'X-Device-Id': `dev-${uuid()}` }, tags: { name: 'authorize' } });
  if (!check(pay, { 'payment created (201)': (r) => r.status === 201 })) return;
  const payment = pay.json();

  // 4. capture the full amount
  let status = payment.status;
  if (status === 'AUTHORIZED') {
    const cap = http.post(`${GATEWAY}/api/payments/${payment.id}/capture`, null,
      { headers: { Authorization: auth.Authorization, 'Idempotency-Key': uuid() }, tags: { name: 'capture' } });
    if (check(cap, { 'captured (200)': (r) => r.status === 200 })) status = cap.json('status');
  }
  checkoutDuration.add(Date.now() - start);
  outcomes.add(1, { status });
  check(status, { 'payment CAPTURED': (s) => s === 'CAPTURED' });
}

// Luhn-valid Visa-like number, 4 + 14 random digits + check digit. The simulator's issuer approves any card it
// doesn't know (Issuer.java), card-vault only checks Luhn.
function randomPan() {
  const digits = [4];
  for (let i = 0; i < 14; i++) digits.push(Math.floor(Math.random() * 10));
  let sum = 0;
  for (let i = 0; i < 15; i++) {   // from the right, the check digit's neighbour is doubled
    let d = digits[14 - i];
    if (i % 2 === 0) { d *= 2; if (d > 9) d -= 9; }
    sum += d;
  }
  digits.push((10 - (sum % 10)) % 10);
  return digits.join('');
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
