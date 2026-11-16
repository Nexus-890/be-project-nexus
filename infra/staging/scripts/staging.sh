#!/usr/bin/env bash
# Linux VM operator entry point. Never source dotenv files or echo resolved config.
set -euo pipefail
set +x
umask 077
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$(git -C "$DIR" rev-parse --show-toplevel)"
ENV_FILE="${NEXUS_ENV_FILE:-$DIR/.env}"
STATE="${NEXUS_STATE_DIR:-$DIR/.state}"
mkdir -p "$STATE"
die() { echo "ERROR: $*" >&2; exit 1; }

apps=(config-service discovery-service api-gateway user-service catalog-service commerce-service auction-service inventory-service notification-service payment-service shipment-service rating-service frontend)
databases=(postgres-user:user_db postgres-user:auth_db postgres-catalog:catalog_db postgres-commerce:commerce_db postgres-commerce:payment_db postgres-auction:auction_db postgres-inventory:inventory_db postgres-operations:notification_db postgres-operations:shipment_db postgres-operations:rating_db)

if [ -z "${RELEASE_TAG:-}" ]; then
  if [ -f "$STATE/current" ]; then RELEASE_TAG="$(cat "$STATE/current")";
  else RELEASE_TAG="$(git -C "$REPO" rev-parse HEAD)"; fi
fi
export RELEASE_TAG
dc() { docker compose --env-file "$ENV_FILE" -f "$DIR/compose.yaml" "$@"; }
case "${1:-}" in
  deploy|rollback|backup|restore-test|tls-init|restart-check)
    exec 9>"$STATE/operation.lock"
    flock -n 9 || die "Another STAGING operation is running"
    ;;
esac
check() {
  [ -f "$ENV_FILE" ] || die "Copy .env.example to $ENV_FILE and fill host settings"
  [ "$(stat -c %a "$ENV_FILE")" = 600 ] || die "Run chmod 600 on the host .env"
  dc config --format json | python3 "$DIR/scripts/check-config.py"
}
services_up() { dc up -d --wait --wait-timeout 240 "$@"; }
read_env() { dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["environment"][sys.argv[2]])' "$1" "$2"; }

case "${1:-help}" in
  check) check ;;
  compose) shift; dc "$@" ;;
  auth-admin)
    check
    shift
    dc run --rm --no-deps --entrypoint bash auth-seed -ec 'set +x; cfg=$(mktemp); trap '\''rm -f "$cfg"'\'' EXIT; /opt/keycloak/bin/kcadm.sh config credentials --config "$cfg" --server "$KEYCLOAK_SERVER_URL" --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null; /opt/keycloak/bin/kcadm.sh "$@" --config "$cfg"' bash "$@"
    ;;
  preflight)
    check
    command -v curl >/dev/null; command -v python3 >/dev/null
    docker info --format 'Docker {{.ServerVersion}}; engine RAM {{.MemTotal}} bytes'
    python3 - "$DIR" <<'PY'
import pathlib, shutil, sys
ram = int(next(line.split()[1] for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))) * 1024
disk = shutil.disk_usage(sys.argv[1])
print(f'Host RAM: {ram / 1024**3:.2f} GiB; disk total/free: {disk.total / 1024**3:.2f}/{disk.free / 1024**3:.2f} GiB')
if ram < 15 * 1024**3 or disk.total < 55 * 1024**3:
    sys.exit('Host is below the issue budget of 16 GB RAM / 60 GB disk; confirm capacity before deploying.')
PY
    ;;
  build)
    check
    # Each tag identifies the checked-out source. Do not tag uncommitted edits.
    [ -z "$(git -C "$REPO" status --porcelain --untracked-files=no)" ] || die "Commit tracked changes before building a release"
    [ "$RELEASE_TAG" = "$(git -C "$REPO" rev-parse HEAD)" ] || die "RELEASE_TAG must equal the checked-out commit SHA"
    dc build --pull "${apps[@]}"
    ;;
  tls-init)
    check
    # Only run before nginx has been started; the temporary listener owns port 80.
    [ -z "$(dc ps -q nginx)" ] || die "nginx is already running; use certbot renew instead"
    dc run --rm --no-deps --entrypoint /bin/sh certbot -c 'mkdir -p /var/www/acme/.well-known/acme-challenge; printf ok > /var/www/acme/.well-known/acme-challenge/health'
    bootstrap="$(dc run --rm -d --no-deps --service-ports -v "$DIR/nginx-bootstrap.conf:/etc/nginx/templates/default.conf.template:ro" nginx)"
    trap 'docker stop "$bootstrap" >/dev/null' EXIT
    dc run --rm --no-deps --entrypoint /bin/sh certbot -c 'exec certbot certonly --non-interactive --agree-tos --email "$ACME_EMAIL" --webroot -w /var/www/acme -d "$DOMAIN"'
    ;;
  deploy)
    check
    candidate="${2:?Usage: staging.sh deploy FULL_COMMIT_SHA}"
    [[ "$candidate" =~ ^[0-9a-f]{40}$ ]] || die "Use a full commit SHA"
    [ "$candidate" = "$(git -C "$REPO" rev-parse HEAD)" ] || die "Check out the candidate source before deploy"
    export RELEASE_TAG="$candidate"
    # Images must already exist. Deployment never silently builds a different tree.
    for app in "${apps[@]}"; do
      image="$(dc config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["image"])' "$app")"
      docker image inspect "$image" >/dev/null
    done
    printf '%s\n' "$candidate" > "$STATE/pending"
    services_up postgres-user postgres-catalog postgres-commerce postgres-auction postgres-inventory postgres-operations redis kafka zipkin auth-service
    dc run --rm auth-seed
    services_up "${apps[@]}"
    services_up nginx certbot
    "$DIR/scripts/staging.sh" verify
    if [ -f "$STATE/current" ]; then cp "$STATE/current" "$STATE/previous"; fi
    printf '%s\n' "$candidate" > "$STATE/current"
    rm -f "$STATE/pending"
    echo "Deployed $candidate. Previous images and all data volumes are retained."
    ;;
  rollback)
    check
    if [ -f "$STATE/pending" ]; then
      [ -s "$STATE/current" ] || die "First deployment failed; there is no previous successful image set"
      previous="$(cat "$STATE/current")"
      failed_candidate=1
    else
      [ -s "$STATE/previous" ] || die "No recorded previous successful release"
      previous="$(cat "$STATE/previous")"
      failed_candidate=0
    fi
    [[ "$previous" =~ ^[0-9a-f]{40}$ ]] || die "Invalid previous release"
    # This only replaces application containers. It never recreates/downgrades DBs.
    # Review migration compatibility before running it (see runbook).
    export RELEASE_TAG="$previous"
    dc up -d --no-deps --no-build --pull never --wait --wait-timeout 240 "${apps[@]}"
    "$DIR/scripts/staging.sh" verify
    if [ "$failed_candidate" -eq 0 ]; then
      current="$(cat "$STATE/current")"
      printf '%s\n' "$previous" > "$STATE/current"
      printf '%s\n' "$current" > "$STATE/previous"
    fi
    rm -f "$STATE/pending"
    echo "Application images rolled back to $previous; database volumes were not changed by this command."
    ;;
  backup)
    check
    destination="${2:?Usage: staging.sh backup NEW_DIRECTORY}"
    [ ! -e "$destination" ] || die "Backup destination must be new"
    mkdir -p "$destination"
    for item in "${databases[@]}"; do
      service="${item%%:*}"; database="${item#*:}"
      dc exec -T "$service" sh -ec 'exec pg_dump -U "$POSTGRES_USER" -d "$1" --format=custom --no-owner --no-acl' sh "$database" > "$destination/$database.dump.partial"
      mv "$destination/$database.dump.partial" "$destination/$database.dump"
    done
    printf '%s\n' "$RELEASE_TAG" > "$destination/release.txt"
    (cd "$destination" && sha256sum ./*.dump > SHA256SUMS)
    echo "Ten logical databases backed up from all six PostgreSQL instances."
    ;;
  restore-test)
    check
    dump="${2:?Usage: staging.sh restore-test DUMP_FILE postgres-GROUP scratch_NAME}"
    service="${3:?Specify PostgreSQL service}"; database="${4:?Specify scratch database}"
    case "$service" in postgres-user|postgres-catalog|postgres-commerce|postgres-auction|postgres-inventory|postgres-operations) ;; *) die "Unknown DB instance" ;; esac
    [[ "$database" =~ ^scratch_[a-z0-9_]+$ ]] || die "Restore target must be a NEW scratch_ database"
    # createdb fails if the target exists. Production database names are rejected.
    dc exec -T "$service" sh -ec 'createdb -U "$POSTGRES_USER" "$1"' sh "$database"
    dc exec -T "$service" sh -ec 'pg_restore -U "$POSTGRES_USER" -d "$1" --exit-on-error --no-owner --no-acl' sh "$database" < "$dump"
    dc exec -T "$service" sh -ec 'psql -U "$POSTGRES_USER" -d "$1" -Atc "SELECT count(*) FROM information_schema.tables WHERE table_schema = '\''public'\''"' sh "$database"
    echo "Restored into $database; compare source and restored tables before dropping this scratch DB."
    ;;
  verify)
    check
    dc ps --all
    # A running process alone is not health evidence.
    for service in "${apps[@]}" postgres-user postgres-catalog postgres-commerce postgres-auction postgres-inventory postgres-operations kafka redis zipkin auth-service nginx; do
      id="$(dc ps -q "$service")"
      [ -n "$id" ] && [ "$(docker inspect --format '{{.State.Health.Status}}' "$id")" = healthy ] || die "$service is not healthy"
    done
    domain="$(read_env nginx DOMAIN)"
    curl --fail --silent --show-error "https://$domain/" >/dev/null
    # Assert a seeded non-empty list, not merely an HTTP 200.
    curl --fail --silent --show-error "https://$domain/api/v1/products?page=0&size=10" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d.get("data"),list) and len(d["data"])>0, "No seeded products returned"'
    code="$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST -H 'Content-Type: application/json' --data '{}' "https://$domain/api/v1/webhooks/sepay")"
    [ "$code" = 401 ] || die "Unsigned SePay POST returned $code, expected 401"
    ;;
  restart-check)
    check
    start=$SECONDS
    dc restart
    until dc exec -T discovery-service bash -ec 'exec 3<>/dev/tcp/127.0.0.1/8761; printf "GET /eureka/apps HTTP/1.0\r\nHost: localhost\r\nAccept: application/json\r\nConnection: close\r\n\r\n" >&3; cat <&3' | python3 "$DIR/scripts/check-eureka.py"; do
      [ "$((SECONDS-start))" -lt 120 ] || die "Not all business services returned to Eureka in 120 seconds"
      sleep 2
    done
    elapsed=$((SECONDS-start))
    [ "$elapsed" -le 120 ] || die "Eureka recovery took $elapsed seconds"
    "$DIR/scripts/staging.sh" verify
    echo "All business services and gateway registered UP after ${elapsed}s."
    ;;
  capacity)
    check
    shift
    dc ps -q | python3 "$DIR/scripts/capacity.py" "$DIR" "$@"
    ;;
  *)
    echo 'Usage: staging.sh {check|preflight|build|tls-init|deploy SHA|rollback|backup NEW_DIR|restore-test FILE PG scratch_NAME|verify|restart-check|capacity --purchase REF --auction REF|compose ARGS...}'
    ;;
esac
