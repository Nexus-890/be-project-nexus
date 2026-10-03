#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="$(mktemp -d)"
cleanup() {
  python3 - "$TMP_ROOT" <<'PY'
import shutil, sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
}
trap cleanup EXIT

TEST_LOCAL_DEV="$TMP_ROOT/infra/local-dev"
TEST_KAFKA_DIR="$TEST_LOCAL_DEV/kafka"
FAKE_BIN="$TMP_ROOT/bin"
mkdir -p "$TEST_KAFKA_DIR" "$FAKE_BIN"
cp "$SOURCE_DIR/provision-topics.sh" "$TEST_KAFKA_DIR/provision-topics.sh"
cp "$SOURCE_DIR/topics.json" "$TEST_KAFKA_DIR/topics.json"
printf 'TZ=UTC\nKAFKA_PORT=29092\n' > "$TEST_LOCAL_DEV/.env"
printf 'services:\n  kafka:\n    image: fake\n' > "$TEST_LOCAL_DEV/compose.yaml"
chmod +x "$TEST_KAFKA_DIR/provision-topics.sh"

cat > "$FAKE_BIN/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
STATE="${FAKE_KAFKA_STATE:?FAKE_KAFKA_STATE is required}"
LOG="${FAKE_KAFKA_LOG:?FAKE_KAFKA_LOG is required}"
scenario="${FAKE_KAFKA_SCENARIO:-ok}"

state_line() {
  local topic="$1"
  [ -f "$STATE" ] || return 1
  awk -F'|' -v t="$topic" '$1 == t { print; found=1; exit } END { exit found ? 0 : 1 }' "$STATE"
}

list_topics() {
  [ -f "$STATE" ] || return 0
  awk -F'|' 'NF { print $1 }' "$STATE"
}

describe_topic() {
  local topic="$1" line partitions rf configs
  line="$(state_line "$topic")" || return 1
  IFS='|' read -r _topic partitions rf configs <<< "$line"
  case "$scenario:$topic" in
    mismatch-partition:nexus.user.events.v1) partitions=3 ;;
    mismatch-config:nexus.user.events.v1) configs="cleanup.policy=compact,retention.ms=604800000" ;;
  esac
  printf 'Topic: %s\tTopicId: fake\tPartitionCount: %s\tReplicationFactor: %s\tConfigs: min.insync.replicas=1,%s\n' "$topic" "$partitions" "$rf" "$configs"
  printf '\tTopic: %s\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\n' "$topic"
}

create_topic() {
  local topic="" partitions="" rf="" configs=()
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --topic) topic="$2"; shift 2 ;;
      --partitions) partitions="$2"; shift 2 ;;
      --replication-factor) rf="$2"; shift 2 ;;
      --config) configs+=("$2"); shift 2 ;;
      *) shift ;;
    esac
  done
  if state_line "$topic" >/dev/null; then
    echo "topic already exists: $topic" >&2
    return 1
  fi
  local config_text
  config_text="$(IFS=,; echo "${configs[*]}")"
  printf '%s|%s|%s|%s\n' "$topic" "$partitions" "$rf" "$config_text" >> "$STATE"
  printf 'CREATE %s\n' "$topic" >> "$LOG"
  printf 'Created topic %s.\n' "$topic"
}

if [ "$1" = "inspect" ]; then
  echo healthy
  exit 0
fi

[ "$1" = "compose" ] || { echo "unexpected docker command: $*" >&2; exit 90; }
shift
while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file|-f) shift 2 ;;
    ps)
      shift
      [ "${1:-}" = "-q" ] && shift
      [ "${1:-}" = "kafka" ] || { echo "unexpected ps args" >&2; exit 90; }
      echo fake-kafka-container
      exit 0 ;;
    exec) shift; break ;;
    *) echo "unexpected compose arg: $*" >&2; exit 90 ;;
  esac
done
[ "${1:-}" = "-T" ] && shift
[ "${1:-}" = "kafka" ] || { echo "unexpected service: ${1:-}" >&2; exit 90; }
shift
cmd="${1:-}"
shift || true
case "$cmd" in
  */kafka-broker-api-versions.sh) echo broker-ok; exit 0 ;;
  */kafka-topics.sh)
    original_args=("$@")
    mode=""; topic=""
    if [ "$scenario" = "native-list-failure" ]; then
      for arg in "$@"; do [ "$arg" = "--list" ] && { echo list-failed >&2; exit 42; }; done
    fi
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --list) mode=list; shift ;;
        --describe) mode=describe; shift ;;
        --create) mode=create; shift ;;
        --topic) topic="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    case "$mode" in
      list) list_topics ;;
      describe) describe_topic "$topic" ;;
      create) create_topic "${original_args[@]}" ;;
      *) echo "missing topic mode" >&2; exit 90 ;;
    esac
    exit $? ;;
esac
echo "unexpected exec command: $cmd" >&2
exit 90
FAKE
chmod +x "$FAKE_BIN/docker"

manifest_count() {
  python3 - "$TEST_KAFKA_DIR/topics.json" <<'PY'
import json, sys
print(len(json.load(open(sys.argv[1]))['topics']))
PY
}

seed_state() {
  local state_file="$1"
  python3 - "$TEST_KAFKA_DIR/topics.json" "$state_file" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1]))
defaults = manifest['defaults']
configs = ','.join(f'{k}={v}' for k, v in defaults['configs'].items())
with open(sys.argv[2], 'w', encoding='utf-8') as f:
    for topic in manifest['topics']:
        f.write(f"{topic['name']}|{defaults['partitions']}|{defaults['replicationFactor']}|{configs}\n")
PY
}

run_case() {
  local name="$1" expected_code="$2" scenario="$3" seed="$4" mode="${5:-run}"
  local state="$TMP_ROOT/$name.state" log="$TMP_ROOT/$name.log" out="$TMP_ROOT/$name.out"
  : > "$state"
  : > "$log"
  [ "$seed" = seeded ] && seed_state "$state"
  set +e
  FAKE_KAFKA_STATE="$state" FAKE_KAFKA_LOG="$log" FAKE_KAFKA_SCENARIO="$scenario" PATH="$FAKE_BIN:$PATH" \
    "$TEST_KAFKA_DIR/provision-topics.sh" $([ "$mode" = check ] && printf '%s' '--check-only') > "$out" 2>&1
  local code=$?
  set -e
  if [ "$code" -ne "$expected_code" ]; then
    echo "case $name failed: exit $code expected $expected_code" >&2
    cat "$out" >&2
    exit 1
  fi
  CASE_STATE="$state"
  CASE_LOG="$log"
  CASE_OUT="$out"
}

expected_count="$(manifest_count)"

run_case create_reread 0 ok empty run
[ "$(wc -l < "$CASE_LOG")" = "$expected_count" ] || { echo create_reread did not create $expected_count topics >&2; exit 1; }
grep -q 'Done. Topics match' "$CASE_OUT"

run_case repeat 0 ok seeded run
[ ! -s "$CASE_LOG" ] || { echo repeat should not create topics >&2; cat "$CASE_LOG" >&2; exit 1; }

run_case mismatch_partition 1 mismatch-partition seeded run
[ ! -s "$CASE_LOG" ] || { echo mismatch_partition should not create topics >&2; exit 1; }
grep -q 'partitions=3 expected=4' "$CASE_OUT"

run_case mismatch_configs 1 mismatch-config seeded run
[ ! -s "$CASE_LOG" ] || { echo mismatch_configs should not create topics >&2; exit 1; }
grep -q "cleanup.policy='compact' expected='delete'" "$CASE_OUT"

run_case missing_checkonly 1 ok empty check
[ ! -s "$CASE_LOG" ] || { echo missing_checkonly should not create topics >&2; exit 1; }
grep -q 'check-only mode will not create it' "$CASE_OUT"

run_case nativecommandfailure 1 native-list-failure seeded run
[ ! -s "$CASE_LOG" ] || { echo nativecommandfailure should not create topics >&2; exit 1; }
grep -q 'Unable to list Kafka topics' "$CASE_OUT"

echo "provision-topics.sh regression tests passed"
