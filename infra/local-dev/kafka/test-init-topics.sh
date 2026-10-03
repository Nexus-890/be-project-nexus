#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="$(mktemp -d)"
cleanup() {
  [ -n "${TMP_ROOT:-}" ] && [ -d "$TMP_ROOT" ] && find "$TMP_ROOT" -mindepth 1 -maxdepth 999 -delete 2>/dev/null || true
  [ -n "${TMP_ROOT:-}" ] && [ -d "$TMP_ROOT" ] && rmdir "$TMP_ROOT" 2>/dev/null || true
}
trap cleanup EXIT

TEST_DIR="$TMP_ROOT/test-kafka"
FAKE_BIN="$TMP_ROOT/bin"
mkdir -p "$TEST_DIR" "$FAKE_BIN"
cp "$SOURCE_DIR/init-topics.sh" "$TEST_DIR/init-topics.sh"
cp "$SOURCE_DIR/topics.conf" "$TEST_DIR/topics.conf"
chmod +x "$TEST_DIR/init-topics.sh"

cat > "$FAKE_BIN/timeout" <<'FAKE_TIMEOUT'
#!/usr/bin/env bash
set -euo pipefail
seconds="$1"
shift
printf 'TIMEOUT %s %s
' "$seconds" "$(basename "$1")" >> "${FAKE_KAFKA_TIMEOUT_LOG:?FAKE_KAFKA_TIMEOUT_LOG is required}"
exec "$@"
FAKE_TIMEOUT
chmod +x "$FAKE_BIN/timeout"

cat > "$FAKE_BIN/kafka-broker-api-versions.sh" <<'FAKE_API'
#!/usr/bin/env bash
set -euo pipefail
if [ "${FAKE_KAFKA_SCENARIO:-ok}" = "api-error" ]; then
  echo api-error >&2
  exit 40
fi
echo broker-ok
FAKE_API
chmod +x "$FAKE_BIN/kafka-broker-api-versions.sh"

cat > "$FAKE_BIN/kafka-topics.sh" <<'FAKE_TOPICS'
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
    mismatch-rf:nexus.user.events.v1) rf=2 ;;
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
  config_text="$(IFS=,; echo "${configs[*]}")"
  printf '%s|%s|%s|%s\n' "$topic" "$partitions" "$rf" "$config_text" >> "$STATE"
  printf 'CREATE %s\n' "$topic" >> "$LOG"
  printf 'Created topic %s.\n' "$topic"
}

original_args=("$@")
mode=""; topic=""
if [ "$scenario" = "cli-list-error" ]; then
  for arg in "$@"; do [ "$arg" = "--list" ] && { echo list-error >&2; exit 41; }; done
fi
if [ "$scenario" = "cli-list-timeout" ]; then
  for arg in "$@"; do [ "$arg" = "--list" ] && exit 124; done
fi
while [ "$#" -gt 0 ]; do
  case "$1" in
    --list) mode=list; shift ;;
    --describe) mode=describe; shift ;;
    --create) mode=create; shift ;;
    --topic) topic="$2"; shift 2 ;;
    --bootstrap-server|--command-config) shift 2 ;;
    *) shift ;;
  esac
done
case "$mode" in
  list) list_topics ;;
  describe) describe_topic "$topic" ;;
  create) create_topic "${original_args[@]}" ;;
  *) echo "missing topic mode" >&2; exit 90 ;;
esac
FAKE_TOPICS
chmod +x "$FAKE_BIN/kafka-topics.sh"

topic_count() {
  grep -c '^topic|' "$TEST_DIR/topics.conf"
}

seed_state() {
  local state_file="$1" mode="${2:-all}"
  local partitions rf cleanup retention line count=0 total
  partitions="$(awk -F'|' '$1 == "default" && $2 == "partitions" { print $3 }' "$TEST_DIR/topics.conf")"
  rf="$(awk -F'|' '$1 == "default" && $2 == "replication.factor" { print $3 }' "$TEST_DIR/topics.conf")"
  cleanup="$(awk -F'|' '$1 == "config" && $2 == "cleanup.policy" { print $3 }' "$TEST_DIR/topics.conf")"
  retention="$(awk -F'|' '$1 == "config" && $2 == "retention.ms" { print $3 }' "$TEST_DIR/topics.conf")"
  total="$(topic_count)"
  : > "$state_file"
  while IFS= read -r line; do
    count=$((count + 1))
    [ "$mode" = "missing-last" ] && [ "$count" -eq "$total" ] && continue
    IFS='|' read -r _ name _owner _key _status _desc <<< "$line"
    printf '%s|%s|%s|cleanup.policy=%s,retention.ms=%s\n' "$name" "$partitions" "$rf" "$cleanup" "$retention" >> "$state_file"
  done < <(grep '^topic|' "$TEST_DIR/topics.conf")
}

run_case() {
  local name="$1" expected_code="$2" scenario="$3" seed="$4" check="${5:-run}" manifest="${6:-$TEST_DIR/topics.conf}" cli_timeout="${7:-3}"
  local state="$TMP_ROOT/$name.state" log="$TMP_ROOT/$name.log" timeout_log="$TMP_ROOT/$name.timeout.log" out="$TMP_ROOT/$name.out"
  : > "$state"
  : > "$log"
  : > "$timeout_log"
  case "$seed" in
    all) seed_state "$state" all ;;
    missing-last) seed_state "$state" missing-last ;;
    empty) : ;;
    *) echo "unknown seed $seed" >&2; exit 1 ;;
  esac
  set +e
  FAKE_KAFKA_STATE="$state" FAKE_KAFKA_LOG="$log" FAKE_KAFKA_TIMEOUT_LOG="$timeout_log" FAKE_KAFKA_SCENARIO="$scenario" \
  PATH="$FAKE_BIN:$PATH" KAFKA_BIN_DIR="$FAKE_BIN" KAFKA_READY_SLEEP=1 KAFKA_READY_ATTEMPTS=1 CLI_TIMEOUT_SECONDS="$cli_timeout" TOPICS_FILE="$manifest" \
    "$TEST_DIR/init-topics.sh" $([ "$check" = check ] && printf '%s' '--check-only') > "$out" 2>&1
  local code=$?
  set -e
  if [ "$code" -ne "$expected_code" ]; then
    echo "case $name failed: exit $code expected $expected_code" >&2
    cat "$out" >&2
    exit 1
  fi
  CASE_STATE="$state"
  CASE_LOG="$log"
  CASE_TIMEOUT_LOG="$timeout_log"
  CASE_OUT="$out"
}

expected_count="$(topic_count)"

run_case fresh 0 ok empty run
[ "$(wc -l < "$CASE_LOG")" = "$expected_count" ] || { echo "fresh did not create $expected_count topics" >&2; exit 1; }
grep -q 'Done. Topics match' "$CASE_OUT"
grep -q 'TIMEOUT 3 kafka-broker-api-versions.sh' "$CASE_TIMEOUT_LOG"
grep -q 'TIMEOUT 3 kafka-topics.sh' "$CASE_TIMEOUT_LOG"

run_case repeat 0 ok all run
[ ! -s "$CASE_LOG" ] || { echo 'repeat should not create topics' >&2; cat "$CASE_LOG" >&2; exit 1; }

run_case newtopic 0 ok missing-last run
[ "$(wc -l < "$CASE_LOG")" = 1 ] || { echo 'newtopic should create exactly one topic' >&2; cat "$CASE_LOG" >&2; exit 1; }
grep -q 'CREATE nexus.training.integration.v1' "$CASE_LOG"

run_case mismatchpartition 1 mismatch-partition all run
[ ! -s "$CASE_LOG" ] || { echo 'mismatchpartition should not create topics' >&2; exit 1; }
grep -q 'partitions=3 expected=4' "$CASE_OUT"

run_case mismatchconfig 1 mismatch-config all run
[ ! -s "$CASE_LOG" ] || { echo 'mismatchconfig should not create topics' >&2; exit 1; }
grep -q "cleanup.policy='compact' expected='delete'" "$CASE_OUT"

run_case mismatchrf 1 mismatch-rf all run
[ ! -s "$CASE_LOG" ] || { echo 'mismatchrf should not create topics' >&2; exit 1; }
grep -q 'replicationFactor=2 expected=1' "$CASE_OUT"

run_case checkmissing 1 ok empty check
[ ! -s "$CASE_LOG" ] || { echo 'checkmissing should not create topics' >&2; exit 1; }
grep -q 'check-only mode will not create it' "$CASE_OUT"

run_case clierrors 1 cli-list-error all run
[ ! -s "$CASE_LOG" ] || { echo 'clierrors should not create topics' >&2; exit 1; }
grep -q 'Unable to list Kafka topics' "$CASE_OUT"

run_case clitimeout 1 cli-list-timeout all run
[ ! -s "$CASE_LOG" ] || { echo 'timeout must not create topics' >&2; exit 1; }
grep -q 'Kafka CLI timed out after 3s' "$CASE_OUT"

run_case apifailure 1 api-error all run
[ ! -s "$CASE_LOG" ] || { echo 'readiness failure must not create topics' >&2; exit 1; }
grep -q 'Kafka is not ready' "$CASE_OUT"

invalid_manifest="$TMP_ROOT/invalid-topics.conf"
printf 'default|partitions|true\ndefault|replication.factor|1\nconfig|cleanup.policy|delete\nconfig|retention.ms|604800000\ntopic|bad.topic|owner|key|demo|desc\n' > "$invalid_manifest"
run_case invalidmanifest 1 ok empty run "$invalid_manifest"
[ ! -s "$CASE_LOG" ] || { echo 'invalidmanifest should not create topics' >&2; exit 1; }
grep -q 'partitions must be a positive integer' "$CASE_OUT"

for variant in extra_default duplicate_default duplicate_config duplicate_topic extra_topic invalid_name empty_topics; do
  cp "$TEST_DIR/topics.conf" "$invalid_manifest"
  case "$variant" in
    extra_default) sed 's/default|partitions|4/default|partitions|4|/' "$TEST_DIR/topics.conf" > "$invalid_manifest" ;;
    duplicate_default) printf 'default|partitions|4\n' >> "$invalid_manifest" ;;
    duplicate_config) printf 'config|cleanup.policy|delete\n' >> "$invalid_manifest" ;;
    duplicate_topic) grep '^topic|' "$TEST_DIR/topics.conf" | head -1 >> "$invalid_manifest" ;;
    extra_topic) printf 'topic|new.topic|owner|key|demo|description|extra\n' >> "$invalid_manifest" ;;
    invalid_name) printf 'topic|..|owner|key|demo|description\n' >> "$invalid_manifest" ;;
    empty_topics) grep -v '^topic|' "$TEST_DIR/topics.conf" > "$invalid_manifest" ;;
  esac
  run_case "$variant" 1 ok empty run "$invalid_manifest"
  [ ! -s "$CASE_LOG" ] && [ ! -s "$CASE_TIMEOUT_LOG" ] || { echo "$variant must fail before Kafka calls" >&2; exit 1; }
  grep -q 'invalid manifest' "$CASE_OUT"
done

run_case invalidtimeout 1 ok empty run "$TEST_DIR/topics.conf" not-a-number
[ ! -s "$CASE_LOG" ] && [ ! -s "$CASE_TIMEOUT_LOG" ] || { echo 'invalid timeout must fail before Kafka calls' >&2; exit 1; }
grep -q 'CLI_TIMEOUT_SECONDS must be a positive integer' "$CASE_OUT"

echo "init-topics.sh fake Kafka CLI regression tests passed"
