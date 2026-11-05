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
