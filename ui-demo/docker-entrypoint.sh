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

# The :443 server block needs a real cert to even start (nginx refuses to boot at all
# otherwise, not just skip that block — "cannot load certificate ... no such file").
# In kind, ui-shop terminates TLS itself using a self-signed cert mounted at
# /etc/nginx/certs (see k8s/kind/*). In production (k8s/hetzner), Traefik's Gateway
# terminates TLS instead and nothing mounts a cert here — so only enable the :443
# block when the cert files actually exist at runtime.
if [ -f /etc/nginx/certs/tls.crt ] && [ -f /etc/nginx/certs/tls.key ]; then
  cp /etc/nginx/nginx-ssl.conf.disabled /etc/nginx/conf.d/nginx-ssl.conf
fi

exec nginx -g 'daemon off;'
