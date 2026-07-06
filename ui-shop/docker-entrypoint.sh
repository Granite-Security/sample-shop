#!/bin/sh
set -e

# Render runtime config (Correction 4). Only the listed vars are substituted so any
# incidental ${...} in the template is left intact. Defaults keep the image usable
# for a plain `docker run` without env vars.
export OIDC_AUTHORITY="${OIDC_AUTHORITY:-http://localhost:8080/auth}"
export OIDC_CLIENT_ID="${OIDC_CLIENT_ID:-spa-client}"
export STRIPE_PUBLISHABLE_KEY="${STRIPE_PUBLISHABLE_KEY:-}"

envsubst '${OIDC_AUTHORITY} ${OIDC_CLIENT_ID} ${STRIPE_PUBLISHABLE_KEY}' \
  < /usr/share/nginx/html/config.template.js \
  > /usr/share/nginx/html/config.js

exec nginx -g 'daemon off;'
