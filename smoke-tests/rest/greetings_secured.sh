#!/bin/bash

COOKIE_JAR=$(mktemp /tmp/iaka_cookies.XXXXXX)
trap 'rm -f "$COOKIE_JAR"' EXIT

# Step 1: Hit secured endpoint → redirect chain → auth-server login page
HTML=$(curl -c "$COOKIE_JAR" -s -L http://localhost:8080/api/secured)

# Step 2: Extract CSRF and submit login
CSRF=$(echo "$HTML" | grep 'name="_csrf"' | awk -F 'value="' '{print $2}' | awk -F '"' '{print $1}')
curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/iaka_login_headers.txt \
  -X POST http://localhost:9090/login \
  -d "username=user&password=password&_csrf=$CSRF" -o /dev/null

# Step 3: Follow redirect to /oauth2/authorize (may show consent page or issue code)
LOCATION=$(grep -i "^Location" /tmp/iaka_login_headers.txt | awk '{print $2}' | tr -d '\r\n')
HTML=$(curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/iaka_auth_headers.txt "$LOCATION" -o /tmp/iaka_auth_body.txt)

# Step 4: If consent page, approve it
if echo "$HTML" | grep -q "Consent required"; then
  STATE=$(echo "$HTML" | grep -o 'name="state" value="[^"]*"' | awk -F 'value="' '{print $2}' | awk -F '"' '{print $1}')
  curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -D /tmp/iaka_consent_headers.txt \
    -X POST http://localhost:9090/oauth2/authorize \
    -d "scope=openid&scope=profile&scope=email&client_id=oidc-client&state=$STATE" \
    -o /dev/null
  LOCATION=$(grep -i "^Location" /tmp/iaka_consent_headers.txt | awk '{print $2}' | tr -d '\r\n')
fi

# Step 5: If we got a redirect to gateway callback, follow it
if echo "$LOCATION" | grep -q "login/oauth2/code"; then
  curl -c "$COOKIE_JAR" -b "$COOKIE_JAR" -s -L "$LOCATION" -o /dev/null
fi

# Step 6: Call the secured endpoint
curl -b "$COOKIE_JAR" -s http://localhost:8080/api/secured
echo ""
