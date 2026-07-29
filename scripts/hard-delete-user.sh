#!/usr/bin/env bash
#
# Hard-deletes a user and everything they own, straight against the databases.
#
# This is the blunt instrument. The application already has a delete that does
# this properly — DELETE /api/profiles/admin/users/{username}, which runs the
# saga, refuses to destroy the order history of anyone who has ever paid, and
# blocks them instead (docs/users/blocking-users.md D1). Use that one for real
# users. This script exists for test/demo accounts and for the identities left
# behind by the Google-`sub` keying gap, and it deliberately honours none of
# those guard rails: it deletes paid orders and their payment records too.
#
# Deleting in FK order, since only authdb cascades:
#   payment.refund, payment.payment, delivery.delivery   (by order id)
#   shop.order_item, shop.customer_order                 (by username)
#   profile.user_file, profile.delivery_address, profile.user_profile
#   auth.users                                           (authorities +
#                                                         password_reset_token
#                                                         cascade)
#
# Storage objects are NOT deleted — Garage is not reachable over psql. Their
# keys are printed at the end so you can remove them deliberately.
#
# Usage:
#   ./scripts/hard-delete-user.sh <username>          # dry run: report only
#   ./scripts/hard-delete-user.sh <username> --yes    # actually delete
#
set -euo pipefail

NAMESPACE="${NAMESPACE:-granite}"
USERNAME="${1:-}"
CONFIRM="${2:-}"

if [[ -z "$USERNAME" ]]; then
    echo "usage: $0 <username> [--yes]" >&2
    exit 1
fi

# Guard against the one-character typo that would empty the platform. Every
# statement below is scoped by username, but a blank or wildcard value would
# still be a very bad afternoon.
if [[ "$USERNAME" == *"%"* || "$USERNAME" == *"'"* || "$USERNAME" == *";"* ]]; then
    echo "refusing: username contains a wildcard or quote character" >&2
    exit 1
fi

echo "context: $(kubectl config current-context)   namespace: $NAMESPACE"
echo

# Pod names change on every restart, so resolve them rather than hardcoding.
pod_for() {
    local name
    name=$(kubectl -n "$NAMESPACE" get pods -o name 2>/dev/null | grep "postgres-$1" | head -1)
    if [[ -z "$name" ]]; then
        echo "could not find a postgres-$1 pod in namespace $NAMESPACE" >&2
        exit 1
    fi
    echo "${name#pod/}"
}

# psql <service> <db> <sql>
psql_run() {
    local svc="$1" db="$2" sql="$3" pod user
    pod=$(pod_for "$svc")
    user=$(kubectl -n "$NAMESPACE" exec "$pod" -- printenv POSTGRES_USER)
    kubectl -n "$NAMESPACE" exec "$pod" -- psql -U "$user" -d "$db" -qAt -c "$sql"
}

echo "── what exists for '$USERNAME' ──────────────────────────────"
AUTH_ROW=$(psql_run auth authdb \
    "SELECT id||'  '||username||'  '||email||'  '||provider FROM users WHERE username = '$USERNAME';")
echo "authdb.users        : ${AUTH_ROW:-(none)}"

ORDER_IDS=$(psql_run shop shopdb \
    "SELECT string_agg(id::text, ',') FROM customer_order WHERE username = '$USERNAME';")
ORDER_COUNT=$(psql_run shop shopdb \
    "SELECT count(*) FROM customer_order WHERE username = '$USERNAME';")
echo "shopdb.customer_order: $ORDER_COUNT order(s)${ORDER_IDS:+  [ids: $ORDER_IDS]}"

PROFILE_COUNT=$(psql_run profile profiledb \
    "SELECT count(*) FROM user_profile WHERE username = '$USERNAME';")
ADDRESS_COUNT=$(psql_run profile profiledb \
    "SELECT count(*) FROM delivery_address WHERE username = '$USERNAME';")
FILE_COUNT=$(psql_run profile profiledb \
    "SELECT count(*) FROM user_file WHERE username = '$USERNAME';")
echo "profiledb           : $PROFILE_COUNT profile, $ADDRESS_COUNT address(es), $FILE_COUNT file(s)"

# Collected before anything is deleted — afterwards the rows that name them are gone.
OBJECT_KEYS=$(psql_run profile profiledb \
    "SELECT object_key FROM user_file WHERE username = '$USERNAME'
     UNION ALL
     SELECT avatar_object_key FROM user_profile
      WHERE username = '$USERNAME' AND avatar_object_key IS NOT NULL;")

if [[ -n "$ORDER_IDS" ]]; then
    PAYMENT_COUNT=$(psql_run payment paymentdb \
        "SELECT count(*) FROM payment WHERE order_id IN ($ORDER_IDS);")
    DELIVERY_COUNT=$(psql_run delivery deliverydb \
        "SELECT count(*) FROM delivery WHERE order_id IN ($ORDER_IDS);")
    echo "paymentdb.payment   : $PAYMENT_COUNT"
    echo "deliverydb.delivery : $DELIVERY_COUNT"
fi
echo

if [[ "$CONFIRM" != "--yes" ]]; then
    echo "DRY RUN — nothing deleted. Re-run with --yes to execute:"
    echo "  $0 $USERNAME --yes"
    exit 0
fi

read -r -p "Type the username again to confirm permanent deletion: " TYPED
if [[ "$TYPED" != "$USERNAME" ]]; then
    echo "aborted: '$TYPED' does not match '$USERNAME'" >&2
    exit 1
fi

echo
echo "── deleting ────────────────────────────────────────────────"

if [[ -n "$ORDER_IDS" ]]; then
    psql_run payment paymentdb  "DELETE FROM refund  WHERE order_id IN ($ORDER_IDS);"
    psql_run payment paymentdb  "DELETE FROM payment WHERE order_id IN ($ORDER_IDS);"
    echo "  payment rows deleted"

    psql_run delivery deliverydb "DELETE FROM delivery WHERE order_id IN ($ORDER_IDS);"
    echo "  delivery rows deleted"

    # order_item has no ON DELETE CASCADE, so it goes first.
    psql_run shop shopdb "DELETE FROM order_item     WHERE order_id IN ($ORDER_IDS);"
    psql_run shop shopdb "DELETE FROM customer_order WHERE username = '$USERNAME';"
    echo "  $ORDER_COUNT order(s) deleted"
fi

psql_run profile profiledb "DELETE FROM user_file        WHERE username = '$USERNAME';"
psql_run profile profiledb "DELETE FROM delivery_address WHERE username = '$USERNAME';"
psql_run profile profiledb "DELETE FROM user_profile     WHERE username = '$USERNAME';"
echo "  profile, addresses and files deleted"

# authorities + password_reset_token are ON DELETE CASCADE.
psql_run auth authdb "DELETE FROM users WHERE username = '$USERNAME';"
echo "  auth user deleted (authorities and reset tokens cascaded)"

echo
echo "── done ────────────────────────────────────────────────────"
if [[ -n "$OBJECT_KEYS" ]]; then
    echo "Storage objects left behind in Garage — delete these deliberately:"
    echo "$OBJECT_KEYS" | sed 's/^/  /'
else
    echo "No storage objects were owned by this user."
fi
echo
echo "Their access token keeps working until it expires (~5 min); auth-server"
echo "has no session revocation. Nothing else needs restarting."
