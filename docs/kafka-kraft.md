# Kafka 4.x KRaft in local development

This document describes the Kafka instance that is actually defined in
`infra/local-dev/compose.yaml`. The examples were verified against the project image
`apache/kafka:4.3.1`; they are not ZooKeeper-era commands copied from an older tutorial.

Run the commands from:

```bash
cd be-project-nexus/infra/local-dev
```

The examples use a POSIX shell. On Windows, run them from WSL or Git Bash. Commands using
`docker run --network host` also require Docker Desktop host networking to be enabled. If
Kafka CLI tools are installed directly on the host, use the same command after
`/opt/kafka/bin/` and keep `--bootstrap-server localhost:29092`.

## 1. Start and inspect the broker

Copy the local environment template and fill every blank value:

```bash
cp .env.example .env
```

Kafka specifically expects:

```dotenv
KAFKA_PORT=29092
```

The other port variables must also be non-empty because Docker Compose interpolates the
whole file before selecting the `kafka` service. Credentials belong only in the ignored
`.env` file.

Start Kafka without starting the rest of the stack:

```bash
docker compose up -d kafka
docker compose ps kafka
```

Expected state:

```text
nexus-kafka   apache/kafka:4.3.1   Up ... (healthy)
```

Follow startup logs when it does not become healthy:

```bash
docker compose logs -f --tail=200 kafka
```

List the services declared by the Compose file and confirm that there is no ZooKeeper
service:

```bash
docker compose config --services
```

The output contains these four services (the display order is not significant):

```text
kafka
mailpit
postgres
redis
```

## 2. KRaft: what replaced ZooKeeper

KRaft is Kafka's own metadata quorum. Controllers maintain cluster metadata in a Raft
log, elect an active controller, and coordinate brokers. Kafka 4.x does not need a
ZooKeeper process or `zookeeper.connect`.

The local Compose node has both roles:

```yaml
KAFKA_NODE_ID: 1
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
```

- The **broker** role accepts producer/consumer requests and stores topic partitions.
- The **controller** role manages metadata and participates in the KRaft quorum.
- `broker,controller` is a **combined node**. It is convenient for one-node local
  development, but it is a single point of failure and cannot scale or restart brokers
  independently from controllers. It is not the target topology for a production cluster.
- This Compose file currently uses a one-voter static quorum. The voter is node `1` at
  `kafka:9093`.

Show the running metadata quorum:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server localhost:9092 describe --status
```

The verified output contained:

```text
LeaderId:      1
CurrentVoters: [{"id": 1, "endpoints": ["CONTROLLER://kafka:9093"]}]
```

Show the effective KRaft environment:

```bash
docker compose exec kafka env | grep '^KAFKA_' | sort
```

## 3. Listeners: why there are two client addresses

The Compose file configures three listeners, but only two are client data listeners:

| Listener | Address advertised to clients | Used by |
|---|---|---|
| `PLAINTEXT` | `kafka:9092` | services and CLI processes inside `nexus-local_nexus-network` |
| `EXTERNAL` | `localhost:29092` | applications and CLI processes running on the host |
| `CONTROLLER` | `kafka:9093` | KRaft controller quorum traffic, not application clients |

`listeners` tells the broker which local ports to bind. `advertised.listeners` tells a
client which address to use after the bootstrap connection returns cluster metadata.

One client address cannot serve both network namespaces:

- The host cannot normally resolve the Compose DNS name `kafka`.
- Inside another container, `localhost` refers to that container, not to the Kafka
  container.

Inspect the resolved listener configuration:

```bash
docker compose config kafka | grep -E 'KAFKA_(ADVERTISED_)?LISTENERS'
```

Expected values:

```text
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093,EXTERNAL://:29092
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,EXTERNAL://localhost:29092
```

`PLAINTEXT` means this local stack has neither transport encryption nor client
authentication. It is a local-development configuration.

## 4. Topics: explicit ownership, no accidental creation

The broker has:

```yaml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

Therefore, sending to a misspelled or missing topic must fail instead of silently creating
a one-partition topic with broker defaults. Topic creation is an infrastructure/schema
operation because the partition count, replication factor, retention, and compaction
policy are part of the event contract.

This repository does not yet contain automated topic provisioning. For local development,
a developer creates the required topics explicitly. In a deployed environment, the
platform/deployment process should provision reviewed topic definitions; application
producers should not depend on auto-creation.

### 4.1 Create from the host, inspect inside the Compose network

The following command uses the published `EXTERNAL` listener:

```bash
docker run --rm --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:29092 \
  --create \
  --topic nexus-kraft-host \
  --partitions 4 \
  --replication-factor 1
```

List and describe the same topic through the internal listener:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --list

docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --describe --topic nexus-kraft-host
```

The verified description reported four partitions, replication factor one, and node `1`
as leader and in-sync replica.

### 4.2 Create inside the network, inspect from the host

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --create \
  --topic nexus-kraft-internal \
  --partitions 2 \
  --replication-factor 1
```

```bash
docker run --rm --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:29092 \
  --describe --topic nexus-kraft-internal
```

This proves that the two listeners are two routes to the same broker and metadata, not two
Kafka clusters.

### 4.3 Show that auto-create is off

```bash
printf 'should-fail\n' | docker run --rm -i --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:29092 \
  --topic nexus-kraft-must-not-autocreate \
  --command-property max.block.ms=3000 \
  --command-property request.timeout.ms=2000
```

The verified run reported `UNKNOWN_TOPIC_OR_PARTITION` followed by:

```text
Topic nexus-kraft-must-not-autocreate not present in metadata after 3000 ms.
```

Confirm that it was not created:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --list | grep nexus-kraft-must-not-autocreate
```

The final command must print nothing.

## 5. Produce and consume through both listeners

### 5.1 Host producer to internal consumer

Produce through `localhost:29092`:

```bash
printf 'host-message-1\nhost-message-2\n' | \
docker run --rm -i --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:29092 \
  --topic nexus-kraft-host
```

Consume through `kafka:9092`:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-host \
  --group nexus-cross-listener-internal \
  --from-beginning \
  --max-messages 2 \
  --formatter-property print.partition=true \
  --formatter-property print.offset=true
```

### 5.2 Internal producer to host consumer

```bash
printf 'internal-message-1\ninternal-message-2\n' | \
docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-internal
```

```bash
docker run --rm --network host apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:29092 \
  --topic nexus-kraft-internal \
  --group nexus-cross-listener-host \
  --from-beginning \
  --max-messages 2 \
  --formatter-property print.partition=true \
  --formatter-property print.offset=true
```

Use a new group name when repeating a `--from-beginning` exercise. `--from-beginning`
chooses the initial position only when that group has no committed offset; it does not
rewind an existing committed group.

## 6. Consumer groups and partition assignment

A consumer group is one logical subscriber identified by `group.id`. Kafka assigns each
partition to at most one active consumer within that group. Two different groups can each
receive the full topic, but two instances in the same group divide its partitions.

For a four-partition topic and two active consumers, the verified assignment was:

```text
consumer 1: nexus-kraft-groups:0,1
consumer 2: nexus-kraft-groups:2,3
```

When consumer 2 was killed, consumer 1 was reassigned all four partitions and received new
records from partitions `0`, `1`, `2`, and `3`. This movement is a group rebalance.

### 6.1 Create the group exercise topic

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --create \
  --topic nexus-kraft-groups \
  --partitions 4 \
  --replication-factor 1
```

### 6.2 Start two consumers

Run this command in terminal A, then run the same command in terminal B:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-groups \
  --group nexus-kraft-workers \
  --from-beginning \
  --formatter-property print.partition=true \
  --formatter-property print.offset=true
```

Inspect the members and assignments from terminal C:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe \
  --group nexus-kraft-workers \
  --members --verbose
```

Produce records across all partitions. `RoundRobinPartitioner` is specified so that the
exercise does not depend on the default sticky batching behavior:

```bash
seq 1 20 | sed 's/^/group-message-/' | \
docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-groups \
  --command-property partitioner.class=org.apache.kafka.clients.producer.RoundRobinPartitioner
```

Stop terminal B with `Ctrl+C`. Re-run the group description command until only one member
remains; its assignment should contain partitions `0,1,2,3`. Produce another batch and
observe that terminal A now prints records from all four partitions.

If there are more active consumers than partitions, the extra consumers remain idle until
a partition becomes available.

## 7. Offsets, commits, and redelivery

An offset identifies a record's position within one partition. A consumer also has a
current in-memory position. The **committed offset** is the next position the group will
resume from after a restart or rebalance; Kafka stores group commits in the compacted
internal topic `__consumer_offsets`.

- With `enable.auto.commit=true`, the client periodically commits offsets according to
  `auto.commit.interval.ms`. Processing must keep pace with what `poll()` returned or the
  commit can move ahead of unfinished work.
- With `enable.auto.commit=false`, application code decides when processing is complete
  and calls a commit API. For database/event handlers, commit only after the side effect
  succeeds.
- If a process finishes a side effect and dies before committing, the replacement starts
  from the previous committed offset. The record is delivered again. This is the duplicate
  side of at-least-once delivery, so handlers must be idempotent. The project's
  `processed_events` tables are intended to support that responsibility.

### 7.1 Reproduce redelivery after a kill before commit

Create a one-partition topic and one record:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --create \
  --topic nexus-kraft-redelivery \
  --partitions 1 \
  --replication-factor 1

printf 'redeliver-me\n' | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-redelivery
```

Start a consumer with auto-commit disabled in the container background:

```bash
docker compose exec -T kafka sh -lc '
  rm -f /tmp/redelivery-first.log /tmp/redelivery-first.pid
  nohup /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server kafka:9092 \
    --topic nexus-kraft-redelivery \
    --group nexus-kraft-redelivery-group \
    --from-beginning \
    --command-property enable.auto.commit=false \
    --command-property session.timeout.ms=6000 \
    --command-property heartbeat.interval.ms=2000 \
    --formatter-property print.partition=true \
    --formatter-property print.offset=true \
    > /tmp/redelivery-first.log 2>&1 < /dev/null &
  echo $! > /tmp/redelivery-first.pid
'
```

Wait until the record appears, then kill the consumer without allowing a commit:

```bash
docker compose exec kafka sh -lc \
  "grep 'redeliver-me' /tmp/redelivery-first.log"

docker compose exec kafka sh -lc \
  'kill -9 $(cat /tmp/redelivery-first.pid)'
```

The verified first delivery was:

```text
Partition:0  Offset:0  redeliver-me
```

Restart the same group:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-redelivery \
  --group nexus-kraft-redelivery-group \
  --from-beginning \
  --max-messages 1 \
  --command-property enable.auto.commit=false \
  --formatter-property print.partition=true \
  --formatter-property print.offset=true
```

The verified restart printed the same record again:

```text
Partition:0  Offset:0  redeliver-me
```

It is the same record, not a second produced copy: partition and offset are identical.

Inspect committed offsets and lag for any group:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --describe --group nexus-kraft-workers
```

## 8. Producer idempotence

`enable.idempotence=true` lets the broker de-duplicate retries made by one producer session.
For example, if the producer sent a batch but lost the acknowledgement, its retry does not
append a second copy of that batch. Kafka requires compatible delivery settings such as
`acks=all`, retries greater than zero, and at most five in-flight requests per connection.

Kafka producers enable idempotence by default when no conflicting setting disables it. We
still set it explicitly in this exercise so the requirement is visible:

```bash
docker compose exec kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 \
  --create \
  --topic nexus-kraft-idempotence \
  --partitions 1 \
  --replication-factor 1

printf 'idempotent-send\n' | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-idempotence \
  --command-property enable.idempotence=true \
  --command-property acks=all \
  --command-property retries=5
```

The client rejects a conflicting configuration before sending:

```bash
printf 'must-not-send\n' | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:9092 \
  --topic nexus-kraft-idempotence \
  --command-property enable.idempotence=true \
  --command-property acks=1
```

Verified error:

```text
ConfigException: Must set acks to all in order to use the idempotent producer.
```

Producer idempotence does **not** protect against:

- application code deliberately calling `send()` twice;
- application-level retries after creating a new producer session;
- a consumer receiving an uncommitted record again;
- duplicate HTTP requests or database side effects;
- atomic writes across Kafka and PostgreSQL;
- atomic multi-topic workflows without Kafka transactions.

Use event IDs, unique/idempotency keys, `processed_events`, and the transactional outbox for
application-level duplicate protection. Use Kafka transactions when a Kafka-to-Kafka flow
requires atomic produced records and committed consumer offsets. `enable.idempotence` alone
is not an end-to-end exactly-once guarantee.

## 9. Daily inspection commands

```bash
# Container state and logs
docker compose ps kafka
docker compose logs --tail=200 kafka

# KRaft quorum
docker compose exec kafka /opt/kafka/bin/kafka-metadata-quorum.sh \
  --bootstrap-server kafka:9092 describe --status

# Topics
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topic TOPIC_NAME

# Consumer groups, assignments, committed offsets, and lag
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --list
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group GROUP_ID
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group GROUP_ID --members --verbose

# Topic end offsets
docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server kafka:9092 --topic TOPIC_NAME
```

Stop Kafka while preserving its named volume:

```bash
docker compose stop kafka
```

Remove the local Kafka data only when a full reset is intentional:

```bash
docker compose down -v
```

`down -v` deletes the named volumes for the whole Compose project, including any local
PostgreSQL and Redis data created by this stack.

## 10. Backend developer exercise sign-off

Every backend developer must complete at least these steps once:

1. Start Kafka and inspect the KRaft quorum.
2. Create and describe a topic explicitly.
3. Produce through one listener and consume through the other.
4. Run two consumers in one group and observe the partition split.
5. Stop one consumer and observe the reassignment.
6. Reproduce delivery of the same partition/offset after a pre-commit kill.

Record completion in the issue or merge request; do not put personal attendance data in
this operational guide.

## 11. Apache Kafka 4.3 references

- [KRaft configuration and combined roles](https://kafka.apache.org/43/operations/kraft/)
- [Differences between KRaft and ZooKeeper mode](https://kafka.apache.org/43/getting-started/zk2kraft/)
- [Listener configuration](https://kafka.apache.org/43/security/listener-configuration/)
- [Broker configuration, including advertised listeners and topic auto-creation](https://kafka.apache.org/43/configuration/broker-configs/)
- [KafkaConsumer groups, commits, and delivery semantics](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)
- [KafkaProducer idempotence and transactions](https://kafka.apache.org/43/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)
