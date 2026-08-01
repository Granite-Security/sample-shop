#!/usr/bin/env bash
#
# End-to-end checkout verification: place -> pay at Stripe -> /sync -> PAID ->
# refund -> REIMBURSED, asserting the multi-provider contract at each step.
#
# This covers what no unit test reaches: the Liquibase migrations against a real
# Postgres, a real Stripe test-mode charge, and the outbox -> Kafka -> shop
# consumer path that carries the payment status back. Written for the local
# compose stack (docs/payment/refactor-payment.md §11).
#
#   docker compose up -d                       # kafka included, or payment will not start
#   scripts/verify-checkout.sh
#
# Requires: jq, a Stripe TEST secret key in $STRIPE_SECRET_KEY (it confirms a real
# PaymentIntent with pm_card_visa), and two accounts whose usernames it reads from
# /tmp/verify_user.txt and /tmp/verify_admin.txt — register them first, and grant
# the second ROLE_ADMIN, since only an admin may refund a merely PAID order.
#
# It writes: one order, one Stripe test charge, one refund. Safe to re-run.
set -uo pipefail

GW=${GW:-http://localhost:8080}
AUTH="$GW/auth"
REDIRECT="http://localhost:5173/callback"
CLIENT="spa-client-shop"
JAR=$(mktemp)
PASS=0; FAIL=0

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
check(){ if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: expected '$3', got '$2'"; fi; }

# ---- token via authorization code + PKCE -------------------------------------
get_token() {
  local user=$1 pass=$2
  local verifier challenge loc csrf code
  verifier=$(LC_ALL=C tr -dc 'a-zA-Z0-9' </dev/urandom | head -c 64)
  challenge=$(printf '%s' "$verifier" | openssl dgst -binary -sha256 | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  rm -f "$JAR"

  local authz="$AUTH/oauth2/authorize?response_type=code&client_id=$CLIENT&redirect_uri=$REDIRECT&scope=openid%20profile%20email&code_challenge=$challenge&code_challenge_method=S256"
  curl -s -c "$JAR" -b "$JAR" -o /dev/null "$authz"

  csrf=$(curl -s -c "$JAR" -b "$JAR" "$AUTH/login" \
        | grep -o 'name="_csrf"[^>]*value="[^"]*"' | sed 's/.*value="//;s/"//' | head -1)
  curl -s -c "$JAR" -b "$JAR" -o /dev/null -X POST "$AUTH/login" \
       --data-urlencode "username=$user" --data-urlencode "password=$pass" \
       --data-urlencode "_csrf=$csrf"

  loc=$(curl -s -c "$JAR" -b "$JAR" -o /dev/null -D - "$authz" | grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: //')
  code=$(printf '%s' "$loc" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  [ -z "$code" ] && { echo "AUTH_FAILED" ; return 1; }

  curl -s -X POST "$AUTH/oauth2/token" \
    -d grant_type=authorization_code -d "code=$code" \
    --data-urlencode "redirect_uri=$REDIRECT" -d "client_id=$CLIENT" \
    -d "code_verifier=$verifier" | jq -r '.access_token // "TOKEN_FAILED"'
}

say "0. Authenticate"
# Freshly registered accounts rather than the seeded user/admin: this compose
# volume has been used before and its seeded passwords no longer match the docs.
VUSER=$(cat /tmp/verify_user.txt); VADMIN=$(cat /tmp/verify_admin.txt)
USER_TOKEN=$(get_token "$VUSER" 'Verify123!')
[ "${USER_TOKEN:0:2}" = "ey" ] && ok "got user JWT ($VUSER)" || { bad "could not get user JWT: $USER_TOKEN"; exit 1; }
ADMIN_TOKEN=$(get_token "$VADMIN" 'Verify123!')
[ "${ADMIN_TOKEN:0:2}" = "ey" ] && ok "got admin JWT ($VADMIN)" || bad "could not get admin JWT"

say "1. GET /api/payments/providers  (step 1, new endpoint)"
PROVIDERS=$(curl -s "$GW/api/payments/providers")
echo "  $PROVIDERS"
check "one provider enabled" "$(jq -r 'length' <<<"$PROVIDERS")" "1"
check "provider id"          "$(jq -r '.[0].id' <<<"$PROVIDERS")" "stripe"
check "confirmation mode"    "$(jq -r '.[0].confirmationMode' <<<"$PROVIDERS")" "CLIENT_SDK"
check "webhook disabled"     "$(jq -r '.[0].webhookEnabled' <<<"$PROVIDERS")" "false"

say "2. GET /actuator/health/providers  (step 1, replaces /health/stripe)"
HEALTH=$(docker compose exec -T payment sh -c 'wget -qO- http://localhost:8062/actuator/health/providers' 2>/dev/null)
echo "  $HEALTH"
check "providers health status" "$(jq -r '.status' <<<"$HEALTH")" "UP"
check "stripe connected"        "$(jq -r '.providers.stripe.stripe' <<<"$HEALTH")" "connected"

say "3. Place an order"
PRODUCT=$(curl -s "$GW/api/shop/products" | jq -r 'if type=="object" then .items[0].id else .[0].id end')
ORDER=$(curl -s -X POST "$GW/api/shop/orders" \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"items\":[{\"productId\":$PRODUCT,\"quantity\":1}],
       \"address\":{\"recipientName\":\"Verify Bot\",\"addressLine1\":\"1 Test St\",\"addressLine2\":\"\",
                    \"city\":\"Zurich\",\"state\":\"\",\"zipCode\":\"8000\",\"country\":\"CH\"}}")
ORDER_ID=$(jq -r '.id' <<<"$ORDER")
echo "  order #$ORDER_ID"
[ "$ORDER_ID" != "null" ] && ok "order placed" || { bad "order not placed: $ORDER"; exit 1; }
check "order currency is CHF (step 2 column + cutover)" "$(jq -r '.currency' <<<"$ORDER")" "CHF"

say "4. Payment created from OrderPlaced  (Kafka path)"
for i in $(seq 1 30); do
  PAY=$(curl -s "$GW/api/payments/intent/$ORDER_ID")
  PI=$(jq -r '.providerPaymentId // empty' <<<"$PAY")
  [ -n "$PI" ] && break
  sleep 2
done
echo "  $(jq -c '{provider,providerPaymentId,status,amount,currency}' <<<"$PAY" 2>/dev/null)"
[ -n "$PI" ] && ok "payment intent created via Kafka" || { bad "no payment after 60s: $PAY"; exit 1; }
check "provider recorded"      "$(jq -r '.provider' <<<"$PAY")" "stripe"
check "payment currency"       "$(jq -r '.currency' <<<"$PAY")" "CHF"
check "providerPayload has clientSecret (step 2 JSON column)" \
      "$(jq -r 'if .providerPayload.clientSecret then "yes" else "no" end' <<<"$PAY")" "yes"
check "alias stripePaymentIntentId mirrors providerPaymentId (step 3)" \
      "$(jq -r 'if .stripePaymentIntentId == .providerPaymentId then "match" else "differ" end' <<<"$PAY")" "match"
check "alias clientSecret mirrors payload (step 3)" \
      "$(jq -r 'if .clientSecret == .providerPayload.clientSecret then "match" else "differ" end' <<<"$PAY")" "match"

say "5. Confirm the charge at Stripe (test mode, pm_card_visa)"
CONF=$(curl -s "https://api.stripe.com/v1/payment_intents/$PI/confirm" \
        -u "$STRIPE_SECRET_KEY:" -d payment_method=pm_card_visa \
        -d "return_url=http://localhost:5173/orders/$ORDER_ID")
STRIPE_STATUS=$(jq -r '.status // .error.message' <<<"$CONF")
check "stripe intent status" "$STRIPE_STATUS" "succeeded"
check "charged in chf"       "$(jq -r '.currency' <<<"$CONF")" "chf"
echo "  amount_received=$(jq -r '.amount_received' <<<"$CONF") (minor units — step 0 helper)"

say "6. POST /sync  (the only confirmation path; no webhook)"
SYNC=$(curl -s -X POST "$GW/api/payments/intent/$ORDER_ID/sync")
check "payment status after sync" "$(jq -r '.status' <<<"$SYNC")" "SUCCEEDED"

say "7. Order reaches PAID  (payment outbox -> Kafka -> shop consumer)"
for i in $(seq 1 30); do
  OSTATUS=$(curl -s -H "Authorization: Bearer $USER_TOKEN" "$GW/api/shop/orders/$ORDER_ID" | jq -r '.status')
  [ "$OSTATUS" = "PAID" ] && break
  sleep 2
done
check "shop order status" "$OSTATUS" "PAID"

say "8. Refund  (admin), order reaches REIMBURSED"
REF=$(curl -s -X POST "$GW/api/shop/orders/$ORDER_ID/refund" -H "Authorization: Bearer $ADMIN_TOKEN")
echo "  $(jq -c '{status}' <<<"$REF" 2>/dev/null || echo "$REF")"
for i in $(seq 1 30); do
  OSTATUS=$(curl -s -H "Authorization: Bearer $USER_TOKEN" "$GW/api/shop/orders/$ORDER_ID" | jq -r '.status')
  [ "$OSTATUS" = "REIMBURSED" ] && break
  sleep 2
done
check "shop order status after refund" "$OSTATUS" "REIMBURSED"

PAY2=$(curl -s "$GW/api/payments/intent/$ORDER_ID")
check "payment status"                 "$(jq -r '.status' <<<"$PAY2")" "REFUNDED"
check "refund providerRefundId set"    "$(jq -r 'if .refund.providerRefundId then "yes" else "no" end' <<<"$PAY2")" "yes"
check "refund alias mirrors (step 3)"  "$(jq -r 'if .refund.stripeRefundId == .refund.providerRefundId then "match" else "differ" end' <<<"$PAY2")" "match"

say "9. Database state  (step 2 schema)"
Q() { docker compose exec -T payment-postgres psql -U myuser -d paymentdb -tAc "$1" 2>/dev/null | tr -d '\r'; }
check "stripe_payment_intent_id dropped" \
      "$(Q "SELECT count(*) FROM information_schema.columns WHERE table_name='payment' AND column_name='stripe_payment_intent_id'")" "0"
check "provider_payload exists"  "$(Q "SELECT count(*) FROM information_schema.columns WHERE table_name='payment' AND column_name='provider_payload'")" "1"
check "provider_event table"     "$(Q "SELECT count(*) FROM information_schema.tables WHERE table_name='provider_event'")" "1"
check "refund.provider_refund_id" "$(Q "SELECT count(*) FROM information_schema.columns WHERE table_name='refund' AND column_name='provider_refund_id'")" "1"
check "payment_attempt row written" "$(Q "SELECT count(*) FROM payment_attempt WHERE order_id=$ORDER_ID")" "1"
echo "  attempt: $(Q "SELECT provider||' '||status||' '||amount||' '||currency FROM payment_attempt WHERE order_id=$ORDER_ID")"
check "current_attempt_id linked" "$(Q "SELECT count(*) FROM payment p JOIN payment_attempt a ON a.id=p.current_attempt_id WHERE p.order_id=$ORDER_ID")" "1"
echo "  provider_payload: $(Q "SELECT left(provider_payload,30) FROM payment WHERE order_id=$ORDER_ID")…"

QS() { docker compose exec -T shop-postgres psql -U myuser -d shopdb -tAc "$1" 2>/dev/null | tr -d '\r'; }
check "shop order currency column" "$(QS "SELECT currency FROM customer_order WHERE id=$ORDER_ID")" "CHF"

say "10. Kafka payload carries both new and legacy keys (step 3)"
EV=$(Q "SELECT payload FROM outbox WHERE aggregate_id='$ORDER_ID' AND event_type='PaymentIntentCreated' LIMIT 1")
echo "  $EV"
check "event has provider"          "$(jq -r 'if .provider then "yes" else "no" end' <<<"$EV")" "yes"
check "event has providerPaymentId" "$(jq -r 'if .providerPaymentId then "yes" else "no" end' <<<"$EV")" "yes"
check "legacy alias still present"  "$(jq -r 'if .stripePaymentIntentId then "yes" else "no" end' <<<"$EV")" "yes"
check "clientSecret NOT on topic (step 0)" "$(jq -r 'if .clientSecret then "leaked" else "absent" end' <<<"$EV")" "absent"

printf '\n\033[1m==== %d passed, %d failed ====\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
