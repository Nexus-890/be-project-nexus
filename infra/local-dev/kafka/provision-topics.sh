#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: infra/local-dev/kafka/provision-topics.sh [--check-only]

Creates missing local Kafka topics from topics.json and validates existing topics.
--check-only validates only; missing or mismatched topics fail the run.
USAGE
}

CHECK_ONLY=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check-only) CHECK_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DEV_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$LOCAL_DEV_DIR/compose.yaml"
ENV_FILE="$LOCAL_DEV_DIR/.env"
MANIFEST="$SCRIPT_DIR/topics.json"
BOOTSTRAP_SERVER="kafka:9092"

die() {
  echo "ERROR: $1" >&2
  exit 1
}

topic_mismatch() {
  echo "ERROR: $1" >&2
  echo "Topic validation failed. Fix mismatches manually; this script never deletes or alters existing topics." >&2
  exit 1
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@" < /dev/null
}

kafka_topics() {
  compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOTSTRAP_SERVER" "$@"
}

contains_topic() {
  local topic="$1"
  printf '%s\n' "$TOPIC_LIST" | awk -v t="$topic" '$0 == t { found=1 } END { exit found ? 0 : 1 }'
}

validate_describe() {
  local name="$1" expected_partitions="$2" expected_rf="$3" expected_configs="$4" desc="$5"
  local summary actual_partitions actual_rf
  summary="$(printf '%s\n' "$desc" | awk 'NR==1{print}')"
  actual_partitions="$(printf '%s\n' "$summary" | sed -n 's/.*PartitionCount: \([0-9][0-9]*\).*/\1/p')"
  actual_rf="$(printf '%s\n' "$summary" | sed -n 's/.*ReplicationFactor: \([0-9][0-9]*\).*/\1/p')"
  [ "$actual_partitions" = "$expected_partitions" ] || topic_mismatch "$name partitions=$actual_partitions expected=$expected_partitions"
  [ "$actual_rf" = "$expected_rf" ] || topic_mismatch "$name replicationFactor=$actual_rf expected=$expected_rf"

  SUMMARY="$summary" EXPECTED_CONFIGS="$expected_configs" python3 - "$name" <<'PY' || exit 1
import os, sys
name = sys.argv[1]
summary = os.environ['SUMMARY']
expected_text = os.environ['EXPECTED_CONFIGS']
expected = {}
for pair in filter(None, expected_text.split(';')):
    key, value = pair.split('=', 1)
    expected[key] = value
actual = {}
marker = 'Configs: '
if marker in summary:
    configs = summary.split(marker, 1)[1].strip()
    for pair in filter(None, (item.strip() for item in configs.split(','))):
        if '=' in pair:
            key, value = pair.split('=', 1)
            actual[key.strip()] = value.strip()
for key, expected_value in expected.items():
    actual_value = actual.get(key)
    if actual_value != expected_value:
        print(
            f"ERROR: {name} {key}={actual_value!r} expected={expected_value!r}",
            file=sys.stderr,
        )
        print(
            "Topic validation failed. Fix mismatches manually; this script never deletes or alters existing topics.",
            file=sys.stderr,
        )
        sys.exit(1)
PY
  printf '%s\n' "$desc"
}

create_topic() {
  local name="$1" partitions="$2" rf="$3" expected_configs="$4"
  local args=(--create --topic "$name" --partitions "$partitions" --replication-factor "$rf")
  local pair
  IFS=';' read -r -a config_pairs <<< "$expected_configs"
  for pair in "${config_pairs[@]}"; do
    [ -n "$pair" ] && args+=(--config "$pair")
  done
  kafka_topics "${args[@]}"
}

echo "==> Validating manifest: $MANIFEST"
TOPICS_TSV="$(python3 - "$MANIFEST" <<'PY'
import json, re, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    data = json.load(f)
def fail(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)
def positive_int(value, path):
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        fail(f'{path} must be a positive integer')
    return value
name_re = re.compile(r'^[A-Za-z0-9._-]+$')
config_key_re = re.compile(r'^[A-Za-z0-9._-]+$')
defaults = data.get('defaults') or {}
expected_configs = defaults.get('configs') or {}
partitions = positive_int(defaults.get('partitions'), 'defaults.partitions')
rf = positive_int(defaults.get('replicationFactor'), 'defaults.replicationFactor')
if not isinstance(expected_configs, dict) or not expected_configs:
    fail('defaults.configs must be a non-empty object')
for key, value in expected_configs.items():
    if not isinstance(key, str) or not config_key_re.match(key):
        fail(f'invalid config key: {key!r}')
    if not isinstance(value, str) or value == '' or any(ch in value for ch in ',;\t\n\r'):
        fail(f'{key}: config value must be a non-empty string without comma, semicolon, tab, or newline')
for required in ('cleanup.policy', 'retention.ms'):
    if required not in expected_configs:
        fail(f'defaults.configs.{required} is required')
topics = data.get('topics')
if not isinstance(topics, list) or not topics:
    fail('topics must be a non-empty list')
seen = set()
for topic in topics:
    if not isinstance(topic, dict):
        fail('each topic must be an object')
    name = topic.get('name')
    if not isinstance(name, str) or name in {'.', '..'} or len(name.encode('utf-8')) > 249 or not name_re.match(name):
        fail(f'invalid topic name: {name!r}')
    if name in seen:
        fail(f'duplicate topic name: {name}')
    seen.add(name)
    status = topic.get('status')
    if status not in {'proposed', 'demo'}:
        fail(f'{name}: status must be proposed or demo')
    for key in ('owner', 'key', 'description'):
        if not isinstance(topic.get(key), str) or not topic[key].strip():
            fail(f'{name}: {key} is required')
    config_pairs = ';'.join(f'{k}={v}' for k, v in expected_configs.items())
    print('\t'.join((name, str(partitions), str(rf), config_pairs, topic['owner'], topic['key'], status)))
PY
)"

[ -f "$ENV_FILE" ] || die "Missing $ENV_FILE. Copy infra/local-dev/.env.example to .env and set local values first."

echo "==> Preflight: kafka container must be healthy and reachable on $BOOTSTRAP_SERVER"
KAFKA_CONTAINER_ID="$(compose ps -q kafka)"
[ -n "$KAFKA_CONTAINER_ID" ] || die "Kafka container is not running for $COMPOSE_FILE"
KAFKA_HEALTH="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$KAFKA_CONTAINER_ID")"
[ "$KAFKA_HEALTH" = "healthy" ] || die "Kafka container health is $KAFKA_HEALTH, expected healthy"
compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server "$BOOTSTRAP_SERVER" >/dev/null
TOPIC_LIST="$(kafka_topics --list)" || die "Unable to list Kafka topics from $BOOTSTRAP_SERVER"

while IFS=$'\t' read -r NAME PARTITIONS RF CONFIGS OWNER KEY STATUS; do
  [ -n "$NAME" ] || continue
  echo "==> Topic: $NAME ($STATUS, owner=$OWNER, key=$KEY)"
  if contains_topic "$NAME"; then
    DESC="$(kafka_topics --describe --topic "$NAME")" || die "Unable to describe existing topic $NAME"
    validate_describe "$NAME" "$PARTITIONS" "$RF" "$CONFIGS" "$DESC"
  else
    if [ "$CHECK_ONLY" -eq 1 ]; then
      topic_mismatch "$NAME is missing (check-only mode will not create it)"
    fi
    echo "Creating $NAME"
    create_topic "$NAME" "$PARTITIONS" "$RF" "$CONFIGS"
    DESC="$(kafka_topics --describe --topic "$NAME")" || die "Created $NAME but could not describe it"
    validate_describe "$NAME" "$PARTITIONS" "$RF" "$CONFIGS" "$DESC"
  fi
done <<< "$TOPICS_TSV"

echo "Done. Topics match $MANIFEST."
