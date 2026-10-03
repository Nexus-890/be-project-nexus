#!/usr/bin/env bash
set -euo pipefail

CHECK_ONLY=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check-only) CHECK_ONLY=1 ;;
    -h|--help)
      echo "Usage: provision-topics.sh [--check-only]"
      exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DEV_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$LOCAL_DEV_DIR/compose.yaml"
ENV_FILE="$LOCAL_DEV_DIR/.env"

[ -f "$ENV_FILE" ] || { echo "ERROR: missing $ENV_FILE. Copy infra/local-dev/.env.example to .env first." >&2; exit 1; }
compose() { docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@" < /dev/null; }

container_id="$(compose ps -q kafka)"
[ -n "$container_id" ] || { echo "ERROR: kafka container is not running" >&2; exit 1; }
health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")"
[ "$health" = "healthy" ] || { echo "ERROR: kafka container health is $health, expected healthy" >&2; exit 1; }

args=(run --rm --no-deps kafka-init)
[ "$CHECK_ONLY" -eq 1 ] && args+=(--check-only)
compose "${args[@]}"
