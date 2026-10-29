#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
prefix="nexus-outbox-test-$$-$RANDOM"
postgres="${prefix}-postgres"
kafka="${prefix}-kafka"
kafka_port=${OUTBOX_TEST_KAFKA_PORT:-$((20000 + RANDOM))}

cleanup() {
    docker rm -fv "$kafka" "$postgres" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

export OUTBOX_TEST_DB_USERNAME=postgres
OUTBOX_TEST_DB_PASSWORD=$(cat /proc/sys/kernel/random/uuid)
export OUTBOX_TEST_DB_PASSWORD

docker run -d --name "$postgres" \
    -e POSTGRES_DB=outbox_test -e POSTGRES_USER="$OUTBOX_TEST_DB_USERNAME" \
    -e POSTGRES_PASSWORD="$OUTBOX_TEST_DB_PASSWORD" \
    -p 127.0.0.1::5432 pgvector/pgvector:pg18 >/dev/null

docker run -d --name "$kafka" -p "127.0.0.1:${kafka_port}:9094" \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=INTERNAL://:9092,EXTERNAL://:9094,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS="INTERNAL://localhost:9092,EXTERNAL://127.0.0.1:${kafka_port}" \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false apache/kafka:4.3.1 >/dev/null

ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec "$postgres" pg_isready -U postgres -d outbox_test >/dev/null 2>&1 \
        && timeout 5 docker exec "$kafka" /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
if [[ "$ready" != true ]]; then
    docker logs "$postgres"
    docker logs "$kafka"
    echo "Disposable PostgreSQL/Kafka did not become ready" >&2
    exit 1
fi

postgres_port=$(docker port "$postgres" 5432/tcp | head -1)
export OUTBOX_TEST_JDBC_URL="jdbc:postgresql://127.0.0.1:${postgres_port##*:}/outbox_test"
export OUTBOX_TEST_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:${kafka_port}"
export OUTBOX_TEST_KAFKA_CONTAINER="$kafka"

cd "$script_dir/.."
bash ./mvnw -pl common-outbox -am -Poutbox-integration verify "$@"
