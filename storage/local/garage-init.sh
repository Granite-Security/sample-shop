#!/usr/bin/env bash
# Bootstraps the LOCAL Garage container for the storage service:
# node layout, access key, `product-media` bucket (public read), CORS.
# Safe to re-run: every step checks before acting.
#
# Prereq: docker compose up -d garage   (from the repo root)
#
# Cluster equivalent is documented in docs/plans/storage.md step 8 —
# run the same commands via kubectl exec into the garage pod.
set -euo pipefail

CONTAINER="${GARAGE_CONTAINER:-garage}"
BUCKET="${STORAGE_S3_BUCKET:-product-media}"
KEY_NAME="${STORAGE_S3_KEY_NAME:-storage-key}"
G() { docker exec "$CONTAINER" /garage "$@"; }

echo "==> Waiting for Garage to answer..."
for i in $(seq 1 30); do
  if G status > /dev/null 2>&1; then break; fi
  if [ "$i" = 30 ]; then echo "Garage did not come up — is the container running?"; exit 1; fi
  sleep 2
done

echo "==> Node layout"
# Plain `garage status` prints the 16-hex node id at the start of its table row
# (handshake INFO logs go to stderr, so stdout is just the table).
NODE_ID="$(G status | grep -oE '\b[a-f0-9]{16}\b' | head -1 || true)"
if [ -z "$NODE_ID" ]; then
  echo "Could not determine node id from: $(G status)"; exit 1
fi
if G layout show 2>/dev/null | grep -q "$NODE_ID"; then
  echo "    layout already assigned ($NODE_ID)"
else
  G layout assign -z dc1 -c 1G "$NODE_ID"
  # layout versions increment on each apply; staged changes get version N+1
  CURRENT="$(G layout show 2>/dev/null | grep -oE 'layout version: [0-9]+' | grep -oE '[0-9]+' | head -1 || echo 0)"
  G layout apply --version "$(( CURRENT + 1 ))"
  echo "    layout assigned ($NODE_ID)"
fi

echo "==> Access key: $KEY_NAME"
if G key list 2>/dev/null | grep -q "$KEY_NAME"; then
  echo "    already exists (secret is only shown at creation — reuse your saved copy)"
else
  G key create "$KEY_NAME"
  echo "    ^^^ SAVE the Key ID + Secret key above into your .env / compose env:"
  echo "        STORAGE_S3_ACCESS_KEY / STORAGE_S3_SECRET_KEY"
fi

echo "==> Bucket: $BUCKET"
if G bucket list 2>/dev/null | grep -q "$BUCKET"; then
  echo "    already exists"
else
  G bucket create "$BUCKET"
fi

echo "==> Permissions"
# The app key can read+write (uploads, deletes, presigning).
G bucket allow --read --write "$BUCKET" --key "$KEY_NAME"
# Public product media: Garage v2 has no anonymous S3-API access — public reads
# are served by the s3_web website endpoint (port 3902), path-style /bucket/key,
# once website access is enabled for the bucket.
G bucket website --allow "$BUCKET"
echo "    $KEY_NAME: read+write on $BUCKET; anonymous reads via s3_web (:3902)"

echo "==> CORS (browser presigned PUTs from the UIs)"
# Garage implements PutBucketCors; the AWS CLI is the simplest driver.
# Allowed origins: local UIs + both production storefronts.
if command -v aws > /dev/null 2>&1; then
  KEY_ID="$(G key info "$KEY_NAME" -o json 2>/dev/null | grep -o '"accessKeyId"[[:space:]]*:[[:space:]]*"[^"]*"' | cut -d'"' -f4)"
  SECRET="${STORAGE_S3_SECRET_KEY:-}"
  if [ -z "$KEY_ID" ] || [ -z "$SECRET" ]; then
    echo "    SKIP: could not resolve key id/secret — set STORAGE_S3_SECRET_KEY and re-run,"
    echo "    or apply CORS by hand:"
    echo "    aws --endpoint-url http://localhost:3900 s3api put-bucket-cors --bucket $BUCKET --cors-configuration file://cors.json"
  else
    AWS_ACCESS_KEY_ID="$KEY_ID" AWS_SECRET_ACCESS_KEY="$SECRET" aws --endpoint-url http://localhost:3900 \
      s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration '{
        "CORSRules": [{
          "AllowedOrigins": ["http://localhost:5173", "http://localhost:8080", "https://granite-security.org", "https://sichocolate.com"],
          "AllowedMethods": ["PUT", "GET", "HEAD"],
          "AllowedHeaders": ["*"],
          "MaxAgeSeconds": 3600
        }]
      }'
    echo "    CORS applied"
  fi
else
  echo "    SKIP: aws CLI not installed — apply CORS later (see comment above)."
fi

echo "==> Done. Sanity check:"
echo "    PUT a file via a presigned URL, then:"
echo "    curl http://$BUCKET.localhost:3902/<key>   (anonymous read, vhost-style)"
