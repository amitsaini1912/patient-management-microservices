#!/bin/sh
# Day 24a: one patient's payment through the whole system, step by step (run sh load-test/start-stack.sh first).
#   login -> billing account (gRPC) -> tokenize a card -> authorize (risk check + card network) -> capture
#   -> partial refund -> read it back -> the audit hash chain still verifies
# Needs curl, python3 and Docker (grpcurl runs from its image, nothing to install).
#   sh load-test/demo.sh
set -eu
cd "$(dirname "$0")/.."
GW=http://localhost:4004
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
uuid() { python3 -c "import uuid; print(uuid.uuid4())"; }

step "1. log in (auth-service via the gateway) -> JWT"
TOKEN=$(curl -sf "$GW/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"testuser@test.com","password":"password123"}' | json 'd["token"]')
echo "token: ${TOKEN%%.*}... (JWT)"
AUTH="Authorization: Bearer $TOKEN"

step "2. billing account for a new patient (gRPC, what patient-service does on registration)"
ACCOUNT=$(docker run --rm --network host -v "$PWD/billing-service/src/main/proto:/proto:ro" \
  fullstorydev/grpcurl:v1.9.1 -plaintext -import-path /proto -proto billing_service.proto \
  -d "{\"patientId\":\"$(uuid)\",\"name\":\"Demo Patient\",\"email\":\"demo@test.com\"}" \
  localhost:9001 BillingService/CreateBillingAccount | json 'd["accountId"]')
echo "billing account: $ACCOUNT"

step "3. tokenize the card at card-vault (the card number goes HERE only; billing never sees it)"
CARD=$(curl -sf "$GW/api/cards" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"pan":"4111111111111111","expiry_month":12,"expiry_year":2030}')
echo "$CARD" | json '"token %s  %s ****%s" % (d["token"], d["brand"], d["last4"])'
CARD_TOKEN=$(echo "$CARD" | json 'd["token"]')

step "4. authorize INR 1,500.00 (risk engine first, then network + issuer; same Idempotency-Key = same payment)"
KEY=$(uuid)
PAY=$(curl -sf "$GW/api/payments" -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"billingAccountId\":\"$ACCOUNT\",\"amount\":\"1500.00\",\"currency\":\"INR\",\"paymentMethodToken\":\"$CARD_TOKEN\"}")
echo "$PAY" | json '"payment %s: %s, network code %s, risk %s via %s" % (d["id"], d["status"], d["networkResponseCode"], (d.get("risk") or {}).get("decision"), (d.get("risk") or {}).get("source"))'
PAYMENT=$(echo "$PAY" | json 'd["id"]')
echo "retry with the same key (a client that timed out):"
curl -sf -D - -o /dev/null "$GW/api/payments" -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d "{\"billingAccountId\":\"$ACCOUNT\",\"amount\":\"1500.00\",\"currency\":\"INR\",\"paymentMethodToken\":\"$CARD_TOKEN\"}" \
  | grep -i -E '^HTTP|idempotent-replayed' | tr -d '\r'

step "5. capture the full amount"
curl -sf -X POST "$GW/api/payments/$PAYMENT/capture" -H "$AUTH" -H "Idempotency-Key: $(uuid)" \
  | json '"%s, captured %s %s" % (d["status"], d["capturedAmount"], d["currency"])'

step "6. refund INR 400.00 of it"
curl -sf "$GW/api/payments/$PAYMENT/refunds" -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -d '{"amount":"400.00","currency":"INR","reason":"one test cancelled"}' \
  | json '"refund %s: %s %s" % (d["id"], d["amount"], d["currency"])'

step "7. read the payment back"
curl -sf "$GW/api/payments/$PAYMENT" -H "$AUTH" \
  | json '"%s: amount %s, captured %s, refunded %s" % (d["status"], d["amount"], d["capturedAmount"], d["refundedAmount"])'

step "8. audit log: every change above is in an HMAC hash chain, anchored in Kafka - does it still verify? (ADMIN, via the gateway)"
curl -sf "$GW/api/audit/verify" -H "$AUTH" | json 'd'

printf '\nMore to look at: Grafana http://localhost:3000 (dashboard "Payments overview"), Prometheus http://localhost:9090,\n'
printf 'load: k6 run -e PROFILE=smoke load-test/checkout.js, failures: sh load-test/chaos.sh kafka (docs/CHAOS.md)\n'
