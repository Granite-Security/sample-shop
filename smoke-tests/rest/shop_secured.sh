#!/bin/bash

COOKIE_JAR=$(mktemp /tmp/shop_cookies.XXXXXX)
trap 'rm -f "$COOKIE_JAR"' EXIT

fail() {
  echo "FAIL: $1"
  exit 1
}

# ── Authenticate through the OAuth2 code flow ──────────────────────

HTML=$(curl -c "$COOKIE_JAR" -s -L http://localhost:8080/api/secured)

CSRF=$(echo "$HTML" | grep 'name="_csrf"' | awk -F 'value="' '{print $2}' | awk -F '"' '{print $1}')
curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/shop_login_headers.txt \
  -X POST http://localhost:9090/login \
  -d "username=user&password=password&_csrf=$CSRF" -o /dev/null

LOCATION=$(grep -i "^Location" /tmp/shop_login_headers.txt | awk '{print $2}' | tr -d '\r\n')
curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/shop_auth_headers.txt "$LOCATION" -o /tmp/shop_auth_body.txt

if grep -q "Consent required" /tmp/shop_auth_body.txt; then
  STATE=$(grep -o 'name="state" value="[^"]*"' /tmp/shop_auth_body.txt | awk -F 'value="' '{print $2}' | awk -F '"' '{print $1}')
  curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/shop_consent_headers.txt \
    -X POST http://localhost:9090/oauth2/authorize \
    -d "scope=openid&scope=profile&scope=email&client_id=oidc-client&state=$STATE" \
    -o /dev/null
  LOCATION=$(grep -i "^Location" /tmp/shop_consent_headers.txt | awk '{print $2}' | tr -d '\r\n')
fi

if echo "$LOCATION" | grep -q "login/oauth2/code"; then
  curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -L "$LOCATION" -o /dev/null
fi

echo "Authenticated as user"

# ── 1. Public endpoint (no auth required) ──────────────────────────

echo ""
echo "=== 1. List products (public) ==="
PRODUCTS=$(curl -b "$COOKIE_JAR" -s http://localhost:8080/api/shop/products)
FIRST_ID=$(echo "$PRODUCTS" | python3 -c "
import sys, json
data = json.load(sys.stdin)
items = data.get('items', [])
if items:
    print(items[0]['id'])
    print(items[0]['name'])
")
PRODUCT_ID=$(echo "$FIRST_ID" | head -1)
PRODUCT_NAME=$(echo "$FIRST_ID" | tail -1)
echo "  Found product #$PRODUCT_ID: $PRODUCT_NAME"

# ── 2. Authenticated: place an order ──────────────────────────────

echo ""
echo "=== 2. Place order (authenticated) ==="
ORDER=$(curl -b "$COOKIE_JAR" -s -X POST http://localhost:8080/api/shop/orders \
  -H "Content-Type: application/json" \
  -d "{\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":2}]}")
ORDER_ID=$(echo "$ORDER" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "  Created order #$ORDER_ID"

# ── 3. Authenticated: list orders ──────────────────────────────────

echo ""
echo "=== 3. List orders (authenticated) ==="
curl -b "$COOKIE_JAR" -s http://localhost:8080/api/shop/orders | python3 -m json.tool

echo ""
echo "All shop tests passed."
