#!/usr/bin/env bash
# Run on the host; Compose supplies .env values to the disposable auth-seed client.
set +x
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
IN_CONTAINER=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file)
      [ "$#" -ge 2 ] || { echo "ERROR: --env-file requires a path" >&2; exit 2; }
      ENV_FILE="$2"; shift 2 ;;
    --container) IN_CONTAINER=1; shift ;;
    -h|--help)
      echo "Usage: seed-users.sh [--env-file PATH]"
      echo "Set the three imported demo users' passwords and the user-service client secret from local-dev/.env."
      exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ "$IN_CONTAINER" -eq 0 ]; then
  [ -f "$ENV_FILE" ] || { echo "ERROR: missing env file: $ENV_FILE" >&2; exit 1; }
  exec docker compose --env-file "$ENV_FILE" -f "$SCRIPT_DIR/../compose.yaml" \
    run --rm --no-deps -T auth-seed
fi

# Do not source .env as shell code or log values. Compose parses dotenv quoting.
for key in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD DEMO_ADMIN_PASSWORD DEMO_SELLER_PASSWORD DEMO_BUYER_PASSWORD \
    KEYCLOAK_USER_SERVICE_CLIENT_SECRET; do
  [ -n "${!key:-}" ] || { echo "ERROR: set $key in the env file" >&2; exit 1; }
done

umask 077
CONFIG_DIR="$(mktemp -d)"
trap 'rm -rf "$CONFIG_DIR"' EXIT
KCADM=/opt/keycloak/bin/kcadm.sh
KC_SERVER="${KEYCLOAK_SERVER_URL:-http://auth-service:8080}"
kc() { "$KCADM" "$@" --config "$CONFIG_DIR/kcadm.config"; }

ready=0
for ((attempt=1; attempt<=30; attempt++)); do
  if kc config credentials --server "$KC_SERVER" --realm master \
      --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1 \
      && kc get realms/nexus >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
[ "$ready" -eq 1 ] || { echo "ERROR: cannot authenticate or realm nexus is not ready; check auth-service and admin credentials" >&2; exit 1; }

names=(admin seller buyer)
ids=(68000000-0000-4000-8000-000000000001 68000000-0000-4000-8000-000000000002 68000000-0000-4000-8000-000000000003)
keys=(DEMO_ADMIN_PASSWORD DEMO_SELLER_PASSWORD DEMO_BUYER_PASSWORD)

# Validate all identities before changing any credential. Never recreate a user.
for i in "${!names[@]}"; do
  user="$(kc get "users/${ids[$i]}" -r nexus --fields username)"
  [[ "$user" == *"\"${names[$i]}@nexus.local\""* ]] || {
    echo "ERROR: unexpected demo identity for ${ids[$i]}; check realm import" >&2; exit 1;
  }
done
for i in "${!names[@]}"; do
  key="${keys[$i]}"
  # Keep the password inside a value= argument, including when it starts with '-'.
  kc update "users/${ids[$i]}/reset-password" -r nexus \
    -s type=password -s "value=${!key}" -s temporary=false -n
  echo "Seeded ${names[$i]}@nexus.local (${ids[$i]})"
done

# Realm đã tạo từ trước không nhận lại realm-nexus.json, nên bật xoay refresh token ở đây (chạy lại được nhiều lần)
kc update realms/nexus -s revokeRefreshToken=true -s refreshTokenMaxReuse=0
echo "Refresh token rotation is on"

# #256: V7 moved user-service role data into Keycloak; upgrade existing imports here.
web_client="$(kc get clients -r nexus -q clientId=nexus-web --fields id --format csv --noquotes)"
[ -n "$web_client" ] || { echo "ERROR: nexus-web client is missing" >&2; exit 1; }

for privilege in PROMOTION.EVENT.MANAGE PROMOTION.EVENT.REGISTER VOUCHER.MANAGE VOUCHER.CLAIM VOUCHER.VIEW; do
  endpoint="clients/$web_client/roles"
  if ! kc get "$endpoint/$privilege" -r nexus >/dev/null 2>&1; then
    kc create "$endpoint" -r nexus -s "name=$privilege" -s composite=false
  fi
  role_json="$(kc get "$endpoint/$privilege" -r nexus)"
  printf '[%s]\n' "$role_json" > "$CONFIG_DIR/promotion-role.json"
  kc create "clients/$web_client/scope-mappings/clients/$web_client" -r nexus -f "$CONFIG_DIR/promotion-role.json"
  case "$privilege" in
    PROMOTION.EVENT.MANAGE) roles=(ADMIN) ;;
    PROMOTION.EVENT.REGISTER|VOUCHER.MANAGE) roles=(ADMIN SELLER) ;;
    VOUCHER.CLAIM) roles=(ADMIN BUYER) ;;
    VOUCHER.VIEW) roles=(ADMIN SELLER BUYER SUPPORT_STAFF) ;;
  esac
  for role in "${roles[@]}"; do
    kc create "roles/$role/composites" -r nexus -f "$CONFIG_DIR/promotion-role.json"
  done
done
echo "Seeded Promotions privileges"

client="$(kc get clients -r nexus -q clientId=user-service --fields id --format csv --noquotes)"
if [ -z "$client" ]; then
  client="$(kc create clients -r nexus -s clientId=user-service -s enabled=true -s publicClient=false \
    -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false -s clientAuthenticatorType=client-secret -i)"
fi
kc update "clients/$client" -r nexus -s "secret=$KEYCLOAK_USER_SERVICE_CLIENT_SECRET" -n
kc add-roles -r nexus --uusername service-account-user-service --cclientid realm-management \
  --rolename manage-users --rolename view-users --rolename query-users --rolename view-realm
echo "Seeded client user-service ($client)"

# CLIENT RIÊNG CHO notification-service: CHỈ ĐƯỢC ĐỌC USER (view-users) ĐỂ LẤY EMAIL NGƯỜI NHẬN (#191)
if [ -n "${KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET:-}" ]; then
  client="$(kc get clients -r nexus -q clientId=notification-service --fields id --format csv --noquotes)"
  if [ -z "$client" ]; then
    client="$(kc create clients -r nexus -s clientId=notification-service -s enabled=true -s publicClient=false \
      -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s implicitFlowEnabled=false \
      -s directAccessGrantsEnabled=false -s clientAuthenticatorType=client-secret -i)"
  fi
  kc update "clients/$client" -r nexus -s "secret=$KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET" -n
  kc add-roles -r nexus --uusername service-account-notification-service --cclientid realm-management \
    --rolename view-users
  echo "Seeded client notification-service ($client)"
else
  echo "Skipped client notification-service: KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET is empty"
fi

if [ -n "${AUCTION_RATING_CLIENT_SECRET:-}" ]; then
  client="$(kc get clients -r nexus -q clientId=nexus-auction-service --fields id --format csv --noquotes)"
  if [ -z "$client" ]; then
    client="$(kc create clients -r nexus -f /opt/nexus/auction-rating-client.json -i)"
  else
    kc update "clients/$client" -r nexus -f /opt/nexus/auction-rating-client.json -n
  fi
  kc update "clients/$client" -r nexus -s "secret=$AUCTION_RATING_CLIENT_SECRET" -n
  echo "Seeded client nexus-auction-service ($client)"
else
  echo "Skipped client nexus-auction-service: AUCTION_RATING_CLIENT_SECRET is empty"
fi
