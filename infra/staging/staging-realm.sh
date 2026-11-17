#!/usr/bin/env bash
set +x
set -euo pipefail

[ -n "${STAGING_HOST:-}" ] || { echo "ERROR: set STAGING_HOST in the env file" >&2; exit 1; }

/bin/bash /opt/nexus/seed-users.sh --container

umask 077
CONFIG_DIR="$(mktemp -d)"
trap 'rm -rf "$CONFIG_DIR"' EXIT
KCADM=/opt/keycloak/bin/kcadm.sh
kc() { "$KCADM" "$@" --config "$CONFIG_DIR/kcadm.config"; }
kc config credentials --server "${KEYCLOAK_SERVER_URL:-http://auth-service:8080}" --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

origin="https://${STAGING_HOST}"
web_client="$(kc get clients -r nexus -q clientId=nexus-web --fields id --format csv --noquotes)"
[ -n "$web_client" ] || { echo "ERROR: nexus-web client is missing" >&2; exit 1; }
kc update "clients/$web_client" -r nexus \
  -s "redirectUris=[\"${origin}/*\"]" \
  -s "webOrigins=[\"${origin}\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=${origin}/*"
kc update realms/nexus -s sslRequired=external
echo "nexus-web accepts ${origin} only"
