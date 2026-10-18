#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: create-topics.sh [--check-only]

Initializes Kafka topics from topics.conf using Kafka CLI tools already present in the Kafka image.
Environment:
  KAFKA_BOOTSTRAP_SERVERS  default: kafka:9092
  KAFKA_BIN_DIR            default: /opt/kafka/bin
  TOPICS_FILE              default: <script-dir>/kafka/topics.conf
  KAFKA_READY_ATTEMPTS     default: 30
  KAFKA_READY_SLEEP        default: 2
  KAFKA_REQUEST_TIMEOUT_MS default: 5000
  KAFKA_DEFAULT_API_TIMEOUT_MS default: 10000
  CLI_TIMEOUT_SECONDS     default: 60
USAGE
}

CHECK_ONLY=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check-only) CHECK_ONLY=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
KAFKA_BIN_DIR="${KAFKA_BIN_DIR:-/opt/kafka/bin}"
TOPICS_FILE="${TOPICS_FILE:-$SCRIPT_DIR/kafka/topics.conf}"
KAFKA_READY_ATTEMPTS="${KAFKA_READY_ATTEMPTS:-30}"
KAFKA_READY_SLEEP="${KAFKA_READY_SLEEP:-2}"
KAFKA_REQUEST_TIMEOUT_MS="${KAFKA_REQUEST_TIMEOUT_MS:-5000}"
KAFKA_DEFAULT_API_TIMEOUT_MS="${KAFKA_DEFAULT_API_TIMEOUT_MS:-10000}"
CLI_TIMEOUT_SECONDS="${CLI_TIMEOUT_SECONDS:-60}"
KAFKA_TOPICS="$KAFKA_BIN_DIR/kafka-topics.sh"
KAFKA_API_VERSIONS="$KAFKA_BIN_DIR/kafka-broker-api-versions.sh"

CLIENT_CONFIG="$(mktemp)"
cleanup() {
  [ -n "${CLIENT_CONFIG:-}" ] && [ -f "$CLIENT_CONFIG" ] && unlink "$CLIENT_CONFIG"
}
trap cleanup EXIT
cat > "$CLIENT_CONFIG" <<EOF_CONFIG
request.timeout.ms=$KAFKA_REQUEST_TIMEOUT_MS
default.api.timeout.ms=$KAFKA_DEFAULT_API_TIMEOUT_MS
EOF_CONFIG

die() { echo "ERROR: $1" >&2; exit 1; }
manifest_error() { die "invalid manifest $TOPICS_FILE: $1"; }
topic_mismatch() {
  echo "ERROR: $1" >&2
  echo "Topic validation failed. Fix mismatches manually; this script never deletes or alters existing topics." >&2
  exit 1
}

is_positive_int() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]] && [ "$1" -le 2147483647 ]
}

validate_runtime_env() {
  is_positive_int "$KAFKA_READY_ATTEMPTS" || die "KAFKA_READY_ATTEMPTS must be a positive integer <= 2147483647"
  is_positive_int "$KAFKA_READY_SLEEP" || die "KAFKA_READY_SLEEP must be a positive integer <= 2147483647"
  is_positive_int "$KAFKA_REQUEST_TIMEOUT_MS" || die "KAFKA_REQUEST_TIMEOUT_MS must be a positive integer <= 2147483647"
  is_positive_int "$KAFKA_DEFAULT_API_TIMEOUT_MS" || die "KAFKA_DEFAULT_API_TIMEOUT_MS must be a positive integer <= 2147483647"
  is_positive_int "$CLI_TIMEOUT_SECONDS" || die "CLI_TIMEOUT_SECONDS must be a positive integer <= 2147483647"
}

validate_topic_name() {
  local name="$1" bytes
  [ -n "$name" ] || return 1
  [ "$name" != "." ] && [ "$name" != ".." ] || return 1
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || return 1
  bytes="$(printf '%s' "$name" | wc -c | tr -d ' ')"
  [ "$bytes" -le 249 ]
}

validate_config_token() {
  local token="$1"
  [ -n "$token" ] || return 1
  [[ "$token" != *"|"* && "$token" != *","* && "$token" != *";"* ]]
}

pipe_count() {
  local text="$1" pipes_only
  pipes_only="${text//[^|]/}"
  printf '%s' "${#pipes_only}"
}

declare PARTITIONS="" REPLICATION_FACTOR=""
declare -a CONFIG_KEYS=() TOPIC_NAMES=() TOPIC_OWNERS=() TOPIC_KEYS=() TOPIC_STATUSES=() TOPIC_DESCRIPTIONS=()
declare -A CONFIG_VALUES=() SEEN_CONFIGS=() SEEN_DEFAULTS=() SEEN_TOPICS=() ACTUAL_CONFIGS=()

parse_manifest() {
  [ -f "$TOPICS_FILE" ] || manifest_error "file does not exist"
  local line_no=0 raw line type field1 field2 field3 field4 rest
  while IFS= read -r raw || [ -n "$raw" ]; do
    line_no=$((line_no + 1))
    line="${raw%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    IFS='|' read -r type field1 field2 field3 field4 rest <<< "$line"
    case "$type" in
      default)
        [ "$(pipe_count "$line")" -eq 2 ] || manifest_error "line $line_no default records require exactly 3 columns"
        [ -n "${type:-}" ] && [ -n "${field1:-}" ] && [ -n "${field2:-}" ] || manifest_error "line $line_no default records require exactly 3 columns"
        [ -z "${SEEN_DEFAULTS[$field1]+x}" ] || manifest_error "line $line_no duplicate default $field1"
        SEEN_DEFAULTS[$field1]=1
        case "$field1" in
          partitions)
            is_positive_int "$field2" || manifest_error "line $line_no partitions must be a positive integer <= 2147483647"
            PARTITIONS="$field2" ;;
          replication.factor)
            is_positive_int "$field2" || manifest_error "line $line_no replication.factor must be a positive integer <= 2147483647"
            REPLICATION_FACTOR="$field2" ;;
          *) manifest_error "line $line_no unknown default $field1" ;;
        esac ;;
      config)
        [ "$(pipe_count "$line")" -eq 2 ] || manifest_error "line $line_no config records require exactly 3 columns"
        [ -n "${type:-}" ] && [ -n "${field1:-}" ] && [ -n "${field2:-}" ] || manifest_error "line $line_no config records require exactly 3 columns"
        [[ "$field1" =~ ^[A-Za-z0-9._-]+$ ]] || manifest_error "line $line_no invalid config key $field1"
        [ -z "${SEEN_CONFIGS[$field1]+x}" ] || manifest_error "line $line_no duplicate config $field1"
        validate_config_token "$field2" || manifest_error "line $line_no invalid config value for $field1"
        SEEN_CONFIGS[$field1]=1
        CONFIG_KEYS+=("$field1")
        CONFIG_VALUES[$field1]="$field2" ;;
      topic)
        [ "$(pipe_count "$line")" -eq 5 ] || manifest_error "line $line_no topic records require exactly 6 columns"
        [ -n "${type:-}" ] && [ -n "${field1:-}" ] && [ -n "${field2:-}" ] && [ -n "${field3:-}" ] && [ -n "${field4:-}" ] && [ -n "${rest:-}" ] || manifest_error "line $line_no topic records require exactly 6 columns"
        validate_topic_name "$field1" || manifest_error "line $line_no invalid topic name $field1"
        [ -z "${SEEN_TOPICS[$field1]+x}" ] || manifest_error "line $line_no duplicate topic $field1"
        [ -n "$field2" ] || manifest_error "line $line_no owner is required"
        [ -n "$field3" ] || manifest_error "line $line_no partition key is required"
        case "$field4" in proposed|demo) ;; *) manifest_error "line $line_no status must be proposed or demo" ;; esac
        [ -n "${rest:-}" ] || manifest_error "line $line_no description is required"
        SEEN_TOPICS[$field1]=1
        TOPIC_NAMES+=("$field1")
        TOPIC_OWNERS+=("$field2")
        TOPIC_KEYS+=("$field3")
        TOPIC_STATUSES+=("$field4")
        TOPIC_DESCRIPTIONS+=("$rest") ;;
      *) manifest_error "line $line_no unknown record type $type" ;;
    esac
  done < "$TOPICS_FILE"

  [ -n "$PARTITIONS" ] || manifest_error "missing default|partitions"
  [ -n "$REPLICATION_FACTOR" ] || manifest_error "missing default|replication.factor"
  [ -n "${CONFIG_VALUES[cleanup.policy]+x}" ] || manifest_error "missing config|cleanup.policy"
  [ -n "${CONFIG_VALUES[retention.ms]+x}" ] || manifest_error "missing config|retention.ms"
  [ "${#TOPIC_NAMES[@]}" -gt 0 ] || manifest_error "topic list is empty"
}

run_cli() {
  local code
  # Allow for JVM cold-start time as well as the client request deadlines.
  if timeout "$CLI_TIMEOUT_SECONDS" "$@"; then
    return 0
  else
    code=$?
    if [ "$code" -eq 124 ]; then
      echo "ERROR: Kafka CLI timed out after ${CLI_TIMEOUT_SECONDS}s; retry Compose when the host is ready" >&2
    else
      echo "ERROR: Kafka CLI failed with exit $code" >&2
    fi
    return "$code"
  fi
}

kafka_topics() {
  run_cli "$KAFKA_TOPICS" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --command-config "$CLIENT_CONFIG" "$@"
}

wait_for_kafka() {
  local attempt=1
  until run_cli "$KAFKA_API_VERSIONS" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --command-config "$CLIENT_CONFIG" >/dev/null 2>&1; do
    if [ "$attempt" -ge "$KAFKA_READY_ATTEMPTS" ]; then
      die "Kafka is not ready at $KAFKA_BOOTSTRAP_SERVERS after $KAFKA_READY_ATTEMPTS attempts"
    fi
    attempt=$((attempt + 1))
    sleep "$KAFKA_READY_SLEEP"
  done
}

contains_topic() {
  local topic="$1"
  printf '%s\n' "$TOPIC_LIST" | awk -v t="$topic" '$0 == t { found=1 } END { exit found ? 0 : 1 }'
}

parse_actual_configs() {
  local summary="$1" config_text pair key value
  ACTUAL_CONFIGS=()
  config_text="${summary#*Configs: }"
  [ "$config_text" != "$summary" ] || config_text=""
  IFS=',' read -r -a pairs <<< "$config_text"
  for pair in "${pairs[@]}"; do
    pair="${pair#${pair%%[![:space:]]*}}"
    pair="${pair%${pair##*[![:space:]]}}"
    [[ "$pair" == *=* ]] || continue
    key="${pair%%=*}"
    value="${pair#*=}"
    ACTUAL_CONFIGS[$key]="$value"
  done
}

validate_describe() {
  local name="$1" desc="$2" summary actual_partitions actual_rf key actual_value expected_value
  summary="$(printf '%s\n' "$desc" | awk 'NR==1{print}')"
  actual_partitions="$(printf '%s\n' "$summary" | sed -n 's/.*PartitionCount: \([0-9][0-9]*\).*/\1/p')"
  actual_rf="$(printf '%s\n' "$summary" | sed -n 's/.*ReplicationFactor: \([0-9][0-9]*\).*/\1/p')"
  [ "$actual_partitions" = "$PARTITIONS" ] || topic_mismatch "$name partitions=$actual_partitions expected=$PARTITIONS"
  [ "$actual_rf" = "$REPLICATION_FACTOR" ] || topic_mismatch "$name replicationFactor=$actual_rf expected=$REPLICATION_FACTOR"
  parse_actual_configs "$summary"
  for key in "${CONFIG_KEYS[@]}"; do
    expected_value="${CONFIG_VALUES[$key]}"
    actual_value="${ACTUAL_CONFIGS[$key]-}"
    [ "$actual_value" = "$expected_value" ] || topic_mismatch "$name $key='$actual_value' expected='$expected_value'"
  done
  printf '%s\n' "$desc"
}

create_topic() {
  local name="$1" key args=()
  args=(--create --topic "$name" --partitions "$PARTITIONS" --replication-factor "$REPLICATION_FACTOR")
  for key in "${CONFIG_KEYS[@]}"; do
    args+=(--config "$key=${CONFIG_VALUES[$key]}")
  done
  kafka_topics "${args[@]}"
}

parse_manifest
validate_runtime_env
echo "==> Manifest OK: ${#TOPIC_NAMES[@]} topics from $TOPICS_FILE"
wait_for_kafka
echo "==> Kafka ready: $KAFKA_BOOTSTRAP_SERVERS"
TOPIC_LIST="$(kafka_topics --list)" || die "Unable to list Kafka topics from $KAFKA_BOOTSTRAP_SERVERS"

for i in "${!TOPIC_NAMES[@]}"; do
  name="${TOPIC_NAMES[$i]}"
  echo "==> Topic: $name (${TOPIC_STATUSES[$i]}, owner=${TOPIC_OWNERS[$i]}, key=${TOPIC_KEYS[$i]})"
  if contains_topic "$name"; then
    desc="$(kafka_topics --describe --topic "$name")" || die "Unable to describe existing topic $name"
    validate_describe "$name" "$desc"
  else
    if [ "$CHECK_ONLY" -eq 1 ]; then
      topic_mismatch "$name is missing (check-only mode will not create it)"
    fi
    echo "Creating $name"
    create_topic "$name"
    desc="$(kafka_topics --describe --topic "$name")" || die "Created $name but could not describe it"
    validate_describe "$name" "$desc"
  fi
done

echo "Done. Topics match $TOPICS_FILE."
