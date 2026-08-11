#!/usr/bin/env bash
#
# Re-uploads the product photographs in assets/pics/ to Garage under their
# ORIGINAL object keys.
#
# Why this exists: Garage stores objects on `garage-pvc`, a local-path volume
# whose reclaim policy is Delete, so `kubectl delete namespace granite` destroys
# every product photo. Liquibase brings the catalogue back — 019-seed-si-
# chocolate.sql restores the product rows including their media JSON — but that
# JSON holds URLs, not images, so without this the restored shop renders a grid
# of broken pictures.
#
# The keys are the whole point. `storage` mints `products/<random-uuid>/<file>`
# on every presign, so re-uploading through the admin UI would produce NEW keys
# and leave the restored rows pointing at objects that do not exist. Only the
# manifest records which key each photo has to go back to.
#
# Objects are streamed from stdin through a throwaway aws-cli pod, the same
# approach reset-data.sh uses to empty the bucket — the S3 API is reached
# in-cluster at http://garage:3900, so no credentials leave the cluster and
# nothing has to be installed locally beyond kubectl.
#
# Usage:
#   scripts/restore-product-media.sh              # dry run: report only
#   scripts/restore-product-media.sh --yes        # upload what is missing
#   scripts/restore-product-media.sh --yes --force  # re-upload everything
#
# Env: NAMESPACE (default granite), BUCKET (default: read from the storage
#      deployment), PICS (default assets/pics).

set -euo pipefail

NAMESPACE="${NAMESPACE:-granite}"
PICS="${PICS:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/assets/pics}"
MANIFEST="$PICS/manifest.tsv"
APPLY=false
FORCE=false

for arg in "$@"; do
    case "$arg" in
        --yes)   APPLY=true ;;
        --force) FORCE=true ;;
        -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

[ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 1; }

echo "── context ─────────────────────────────────────────────────"
kubectl config current-context
echo "namespace: $NAMESPACE"

# The bucket is named after its public domain on the cluster and
# "product-media" everywhere else, so ask the running service rather than
# guessing which overlay is deployed.
if [ -z "${BUCKET:-}" ]; then
    BUCKET="$(kubectl -n "$NAMESPACE" exec deploy/storage -- printenv STORAGE_S3_BUCKET 2>/dev/null | tr -d '\r' || true)"
fi
[ -n "$BUCKET" ] || { echo "could not determine the bucket; set BUCKET=" >&2; exit 1; }
echo "bucket:    $BUCKET"
echo

# Fail before touching anything if a photo is missing locally: a partial
# restore is worse than none, because the gaps are invisible until someone
# opens the storefront.
missing=0
while IFS=$'\t' read -r file key ctype; do
    [ -n "${file:-}" ] || continue
    [ -f "$PICS/$file" ] || { echo "MISSING locally: $file"; missing=$((missing + 1)); }
done < "$MANIFEST"
[ "$missing" -eq 0 ] || { echo; echo "$missing file(s) missing from $PICS — aborting" >&2; exit 1; }

AK="$(kubectl -n "$NAMESPACE" get secret granite-secrets -o jsonpath='{.data.storage-s3-access-key}' | base64 -d)"
SK="$(kubectl -n "$NAMESPACE" get secret granite-secrets -o jsonpath='{.data.storage-s3-secret-key}' | base64 -d)"
[ -n "$AK" ] && [ -n "$SK" ] || { echo "no S3 credentials in secret granite-secrets" >&2; exit 1; }

POD="media-restore-$$"
cleanup() { kubectl -n "$NAMESPACE" delete pod "$POD" --ignore-not-found --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "── starting helper pod $POD ────────────────────────────────"
kubectl -n "$NAMESPACE" run "$POD" --restart=Never --image=amazon/aws-cli:latest \
    --env="AWS_ACCESS_KEY_ID=$AK" \
    --env="AWS_SECRET_ACCESS_KEY=$SK" \
    --env="AWS_DEFAULT_REGION=garage" \
    --command -- sleep 1800 >/dev/null
kubectl -n "$NAMESPACE" wait --for=condition=Ready "pod/$POD" --timeout=120s >/dev/null
echo "ready"
echo

# Two helpers on purpose. Anything reading the manifest loop's stdin would eat
# the rows it has not read yet — kubectl exec -i forwards stdin to the pod, so a
# probe run inside the loop silently consumed the rest of the file and the loop
# ended after one image. Probes get /dev/null; only the upload gets a file.
aws_probe()  { kubectl -n "$NAMESPACE" exec -i "$POD" -- aws --endpoint-url http://garage:3900 "$@" < /dev/null; }
aws_upload() { kubectl -n "$NAMESPACE" exec -i "$POD" -- aws --endpoint-url http://garage:3900 "$@"; }

uploaded=0; skipped=0; failed=0
while IFS=$'\t' read -r file key ctype <&3; do
    [ -n "${file:-}" ] || continue
    local_size="$(wc -c < "$PICS/$file" | tr -d ' ')"

    remote_size="$(aws_probe s3api head-object --bucket "$BUCKET" --key "$key" \
        --query ContentLength --output text 2>/dev/null | tr -d '\r' || true)"

    if [ -n "$remote_size" ] && [ "$remote_size" != "None" ] && [ "$FORCE" = false ]; then
        if [ "$remote_size" = "$local_size" ]; then
            printf 'skip    %-46s already present (%s bytes)\n' "$file" "$remote_size"
        else
            printf 'SKIP    %-46s present but %s bytes remote vs %s local — use --force\n' \
                "$file" "$remote_size" "$local_size"
        fi
        skipped=$((skipped + 1))
        continue
    fi

    if [ "$APPLY" = false ]; then
        printf 'would   %-46s -> s3://%s/%s (%s bytes)\n' "$file" "$BUCKET" "$key" "$local_size"
        uploaded=$((uploaded + 1))
        continue
    fi

    if aws_upload s3 cp - "s3://$BUCKET/$key" --content-type "$ctype" < "$PICS/$file" >/dev/null 2>&1; then
        # Verified by size rather than trusting the exit code: a truncated
        # stream through kubectl exec can still exit 0.
        check="$(aws_probe s3api head-object --bucket "$BUCKET" --key "$key" \
            --query ContentLength --output text 2>/dev/null | tr -d '\r' || true)"
        if [ "$check" = "$local_size" ]; then
            printf 'ok      %-46s %s bytes\n' "$file" "$check"
            uploaded=$((uploaded + 1))
        else
            printf 'FAILED  %-46s uploaded but reads back as %s, expected %s\n' "$file" "${check:-nothing}" "$local_size"
            failed=$((failed + 1))
        fi
    else
        printf 'FAILED  %-46s upload rejected\n' "$file"
        failed=$((failed + 1))
    fi
done 3< "$MANIFEST"

echo
if [ "$APPLY" = false ]; then
    echo "dry run — $uploaded to upload, $skipped already present. Re-run with --yes."
else
    echo "$uploaded uploaded, $skipped skipped, $failed failed."
fi
[ "$failed" -eq 0 ]
