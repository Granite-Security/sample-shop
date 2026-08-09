#!/usr/bin/env bash
#
# Resets the platform to a factory-fresh state: every database emptied, every
# stored object removed, seed data back as it was on day one.
#
# It does NOT delete rows table by table. It drops each service's `public`
# schema and restarts the owning service so Liquibase rebuilds the schema and
# replays the seed changesets (users, products, shipping providers, ...). That
# way this script never has to be kept in sync with the migrations — a new
# table is wiped the day it is added.
#
# What it touches:
#   authdb  shopdb  paymentdb  deliverydb  profiledb  notificationdb  balancedb
#   Garage bucket `product-media`  (objects only — bucket, key, CORS and
#                                   website settings are left intact)
#   Kafka domain topics            (only with --kafka)
#
# What it does NOT touch: registered OAuth2 clients (in-memory in auth-server),
# Kubernetes secrets, Garage access keys, Stripe/PayPal — refunds and charges
# already made at the provider stay made. Wiping paymentdb does not un-charge
# anyone; it only makes this platform forget.
#
# Usage:
#   docs/reset-data.sh                    # dry run against k8s: report only
#   docs/reset-data.sh --yes              # do it
#   docs/reset-data.sh --target compose   # local docker compose stack instead
#   docs/reset-data.sh --yes --kafka      # also delete the domain topics
#   docs/reset-data.sh --yes --keep-storage   # leave uploaded media alone
#
# Env: NAMESPACE (default granite), BUCKET (default product-media).
#
set -uo pipefail

TARGET="k8s"
CONFIRM="no"
KAFKA="no"
KEEP_STORAGE="no"
NAMESPACE="${NAMESPACE:-granite}"
BUCKET="${BUCKET:-product-media}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes)           CONFIRM="yes" ;;
        --kafka)         KAFKA="yes" ;;
        --keep-storage)  KEEP_STORAGE="yes" ;;
        --target)        TARGET="${2:-}"; shift ;;
        -h|--help)       sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 1 ;;
    esac
    shift
done

if [[ "$TARGET" != "k8s" && "$TARGET" != "compose" ]]; then
    echo "--target must be k8s or compose" >&2
    exit 1
fi

# service | database | k8s postgres deployment | compose postgres service
DBS=(
    "auth-server|authdb|postgres-auth|postgres"
    "shop|shopdb|postgres-shop|shop-postgres"
    "payment|paymentdb|postgres-payment|payment-postgres"
    "delivery|deliverydb|postgres-delivery|delivery-postgres"
    "profile|profiledb|postgres-profile|profile-postgres"
    "notification|notificationdb|postgres-notification|notification-postgres"
    "balance|balancedb|postgres-balance|balance-postgres"
)

TOPICS=(orders.events payments.events delivery.events shipments.events identity.events)

field() { echo "$1" | cut -d'|' -f"$2"; }

# ---- target-specific plumbing ------------------------------------------------

if [[ "$TARGET" == "k8s" ]]; then
    CONTEXT=$(kubectl config current-context 2>/dev/null) || {
        echo "kubectl is not configured" >&2; exit 1; }

    # psql <row> <sql>
    psql_run() {
        local db pg user
        db=$(field "$1" 2); pg=$(field "$1" 3)
        user=$(kubectl -n "$NAMESPACE" exec "deploy/$pg" -- printenv POSTGRES_USER 2>/dev/null | tr -d '\r')
        [[ -z "$user" ]] && { echo "0"; return 1; }
        kubectl -n "$NAMESPACE" exec "deploy/$pg" -- psql -U "$user" -d "$db" -qAt -c "$2" 2>/dev/null
    }
    app_stop()  { kubectl -n "$NAMESPACE" scale "deploy/$1" --replicas=0 >/dev/null 2>&1; }
    app_start() { kubectl -n "$NAMESPACE" scale "deploy/$1" --replicas=1 >/dev/null 2>&1; }
    app_exists(){ kubectl -n "$NAMESPACE" get "deploy/$1" >/dev/null 2>&1; }
    kafka_exec(){ kubectl -n "$NAMESPACE" exec deploy/kafka -- "$@"; }
    KAFKA_BOOTSTRAP="localhost:9092"
else
    CONTEXT="docker compose ($(pwd))"
    psql_run() {
        local db pg user
        db=$(field "$1" 2); pg=$(field "$1" 4)
        user=$(docker compose exec -T "$pg" printenv POSTGRES_USER 2>/dev/null | tr -d '\r')
        [[ -z "$user" ]] && { echo "0"; return 1; }
        docker compose exec -T "$pg" psql -U "$user" -d "$db" -qAt -c "$2" 2>/dev/null
    }
    app_stop()  { docker compose stop "$1" >/dev/null 2>&1; }
    app_start() { docker compose start "$1" >/dev/null 2>&1; }
    app_exists(){ docker compose ps --services 2>/dev/null | grep -qx "$1"; }
    kafka_exec(){ docker compose exec -T kafka "$@"; }
    KAFKA_BOOTSTRAP="localhost:29092"
fi

# Counts every row in every user table — the number the operator is about to
# destroy, without this script knowing a single table name. query_to_xml runs a
# real count per table rather than trusting pg_stat_user_tables, whose n_live_tup
# is an estimate that reads as 0 on a freshly restored database.
count_rows() {
    psql_run "$1" "
        SELECT COALESCE(sum(c), 0) FROM (
          SELECT (xpath('/row/cnt/text()',
                        query_to_xml(format('SELECT count(*) AS cnt FROM %I.%I',
                                            table_schema, table_name),
                                     false, true, '')))[1]::text::bigint AS c
          FROM information_schema.tables
          WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
        ) t;" | tr -d '[:space:]'
}

# ---- report ------------------------------------------------------------------

echo "target : $TARGET"
echo "context: $CONTEXT${NAMESPACE:+   namespace: $NAMESPACE}"
echo
echo "── what will be destroyed ──────────────────────────────────"

TOTAL=0
for row in "${DBS[@]}"; do
    n=$(count_rows "$row")
    [[ -z "$n" ]] && n="unreachable"
    printf '  %-16s %-16s %s row(s)\n' "$(field "$row" 1)" "$(field "$row" 2)" "$n"
    [[ "$n" =~ ^[0-9]+$ ]] && TOTAL=$((TOTAL + n))
done
echo "  ---"
printf '  %-33s %s row(s)\n' "total" "$TOTAL"

if [[ "$KEEP_STORAGE" == "no" ]]; then
    echo "  storage bucket '$BUCKET': all objects (product images, avatars, user files)"
fi
if [[ "$KAFKA" == "yes" ]]; then
    echo "  kafka topics: ${TOPICS[*]}"
fi
echo

if [[ "$CONFIRM" != "yes" ]]; then
    echo "DRY RUN — nothing changed. Re-run with --yes to execute."
    exit 0
fi

# The default kubectl context is production. Make the operator say where they
# are before anything is dropped.
echo "This permanently destroys all data in: $CONTEXT"
read -r -p "Type RESET to confirm: " TYPED
if [[ "$TYPED" != "RESET" ]]; then
    echo "aborted." >&2
    exit 1
fi

# ---- stop the writers --------------------------------------------------------

echo
echo "── stopping services ───────────────────────────────────────"
STOPPED=()
for row in "${DBS[@]}"; do
    svc=$(field "$row" 1)
    if app_exists "$svc"; then
        app_stop "$svc"
        STOPPED+=("$svc")
        echo "  $svc stopped"
    else
        echo "  $svc not deployed here — skipping (its schema is still wiped)"
    fi
done

# ---- wipe the databases ------------------------------------------------------

echo
echo "── dropping schemas ────────────────────────────────────────"
for row in "${DBS[@]}"; do
    db=$(field "$row" 2)
    # Postgres 15+ no longer grants CREATE on a recreated public schema to
    # anyone but its owner, so the grants are restated: without them Liquibase
    # comes back up and fails on the first CREATE TABLE.
    if psql_run "$row" "
        DROP SCHEMA IF EXISTS public CASCADE;
        CREATE SCHEMA public;
        GRANT ALL ON SCHEMA public TO CURRENT_USER;
        GRANT ALL ON SCHEMA public TO public;" >/dev/null; then
        echo "  $db wiped"
    else
        echo "  $db FAILED — check it by hand" >&2
    fi
done

# ---- wipe stored objects -----------------------------------------------------

if [[ "$KEEP_STORAGE" == "no" ]]; then
    echo
    echo "── emptying storage bucket '$BUCKET' ───────────────────────"
    # Garage's own CLI can create and delete buckets but cannot delete objects,
    # so this goes through the S3 API with the storage service's own key.
    if [[ "$TARGET" == "k8s" ]]; then
        AK=$(kubectl -n "$NAMESPACE" get secret granite-secrets \
             -o jsonpath='{.data.storage-s3-access-key}' 2>/dev/null | base64 -d)
        SK=$(kubectl -n "$NAMESPACE" get secret granite-secrets \
             -o jsonpath='{.data.storage-s3-secret-key}' 2>/dev/null | base64 -d)
        if [[ -n "$AK" && -n "$SK" ]]; then
            kubectl -n "$NAMESPACE" run "s3-wipe-$RANDOM" --rm -i --restart=Never \
                --image=amazon/aws-cli:latest \
                --env="AWS_ACCESS_KEY_ID=$AK" \
                --env="AWS_SECRET_ACCESS_KEY=$SK" \
                --env="AWS_DEFAULT_REGION=garage" \
                --command -- aws --endpoint-url http://garage:3900 \
                s3 rm "s3://$BUCKET" --recursive \
                && echo "  objects deleted" \
                || echo "  storage wipe FAILED — see docs/plans/storage.md" >&2
        else
            echo "  could not read storage keys from secret granite-secrets — skipped" >&2
        fi
    else
        AK="${STORAGE_S3_ACCESS_KEY:-}"; SK="${STORAGE_S3_SECRET_KEY:-}"
        if [[ -z "$AK" || -z "$SK" ]]; then
            echo "  set STORAGE_S3_ACCESS_KEY / STORAGE_S3_SECRET_KEY (same values as"
            echo "  compose uses) to wipe local storage — skipped" >&2
        elif command -v aws >/dev/null 2>&1; then
            AWS_ACCESS_KEY_ID="$AK" AWS_SECRET_ACCESS_KEY="$SK" AWS_DEFAULT_REGION=garage \
                aws --endpoint-url http://localhost:3900 s3 rm "s3://$BUCKET" --recursive \
                && echo "  objects deleted"
        else
            docker run --rm --network container:garage \
                -e "AWS_ACCESS_KEY_ID=$AK" -e "AWS_SECRET_ACCESS_KEY=$SK" \
                -e AWS_DEFAULT_REGION=garage amazon/aws-cli:latest \
                --endpoint-url http://localhost:3900 s3 rm "s3://$BUCKET" --recursive \
                && echo "  objects deleted"
        fi
    fi
fi

# ---- wipe the event log ------------------------------------------------------

if [[ "$KAFKA" == "yes" ]]; then
    echo
    echo "── deleting kafka topics ───────────────────────────────────"
    # Outbox rows are gone with the schemas, but messages already relayed still
    # sit in the log. Consumer offsets are committed, so nothing replays on its
    # own — this is for a genuinely empty kafka-ui, and to drop the reset tokens
    # that identity.events carries in the clear.
    for t in "${TOPICS[@]}"; do
        if kafka_exec kafka-topics --bootstrap-server "$KAFKA_BOOTSTRAP" \
             --delete --topic "$t" >/dev/null 2>&1; then
            echo "  $t deleted"
        else
            echo "  $t not present"
        fi
    done
    echo "  (topics are auto-created again when producers reconnect)"
fi

# ---- bring the services back -------------------------------------------------

echo
echo "── restarting services ─────────────────────────────────────"
for svc in "${STOPPED[@]}"; do
    app_start "$svc"
    echo "  $svc starting — Liquibase will rebuild its schema and reseed"
done

echo
echo "── done ────────────────────────────────────────────────────"
echo "Seed data is back: user/user, admin/admin, manager/manager, the catalog"
echo "products and the shipping providers. Everything else is gone."
echo
echo "Every session is dead — auth-server generates a new RSA key pair on each"
echo "start, so existing tokens no longer verify. Sign in again in the browser."
echo
if [[ "$TARGET" == "k8s" ]]; then
    echo "Watch it come back with:"
    echo "  kubectl -n $NAMESPACE get pods -w"
    echo
    echo "The gateway holds sessions and downstream connections from before the"
    echo "wipe; if it 500s, restart it (docs: gateway restart after crash-loop):"
    echo "  kubectl -n $NAMESPACE rollout restart deploy/gateway"
fi
