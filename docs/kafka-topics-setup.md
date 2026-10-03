# Kafka topics setup trong project Nexus

Tài liệu này dùng sau khi đã đọc `docs/kafka-kraft.md`. File KRaft giải thích Kafka 4.x chạy như thế nào; file này giải thích cách project Nexus khai báo topic, chia partition, chạy smoke flow producer -> consumer, và xem kết quả trên Kafka UI.

## Chạy nhanh trên máy mỗi developer

1. Điền `infra/local-dev/.env` một lần (xem ví dụ ở mục **Chuẩn bị local infra**).
2. Trong `be-project-nexus\infra\local-dev`, chạy `docker compose --profile debug up -d`. Compose tự tạo 7 topic, mỗi topic 4 partition; **không cần chạy `.ps1`**.
3. Trong IntelliJ IDEA, chạy `config-service` (không profile), `notification-service` và `commerce-service` (cùng profile `kafka-smoke`).
4. POST `http://localhost:8083/internal/kafka-smoke/messages` với JSON `{"key":"order-demo-001","message":"hello-kafka"}`. GET `http://localhost:8086/internal/kafka-smoke/messages` để thấy record đó.
5. Mở `http://localhost:18090` → **Topics** → `nexus.training.integration.v1` → **Messages** để xem cùng `eventId`, partition, offset.

Đây là Kafka riêng của từng máy, nhưng cả team dùng cùng định nghĩa topic/partition trong Git. Các lệnh chi tiết và cách kiểm tra nằm bên dưới.

## Phạm vi hiện tại

- Kafka chạy bằng `infra/local-dev/compose.yaml`, không có ZooKeeper.
- Topic contract local nằm ở `infra/local-dev/kafka/topics.conf`.
- Compose tự chạy one-shot service `kafka-init` để tạo/validate topic từ `topics.conf`; developer không cần chạy script hằng ngày.
- Sáu business topic đang là scaffold để thống nhất tên topic và partition trước khi viết handler domain thật.
- Flow đang chạy end-to-end trong branch này là smoke flow:
  - producer: `commerce-service`
  - topic: `nexus.training.integration.v1`
  - consumer: `notification-service`
- Profile `kafka-smoke` chỉ dùng để demo transport Kafka. Profile này tắt database auto-config và Eureka client, nên không ghi outbox, không ghi database, không dedup bằng `processed_events`.

## Topic contract

Tất cả topic trong manifest local dùng cùng default:

| Setting | Giá trị local |
|---|---|
| Partitions | `4` |
| Replication factor | `1` |
| `cleanup.policy` | `delete` |
| `retention.ms` | `604800000` / 7 ngày |

| Topic | Owner | Key | Trạng thái | Mục đích |
|---|---|---|---|---|
| `nexus.user.events.v1` | `user-service` | `userId` | proposed | User domain events sau này |
| `nexus.catalog.product.events.v1` | `catalog-service` | `productId` | proposed | Product domain events sau này |
| `nexus.commerce.order.events.v1` | `commerce-service` | `orderId` | proposed | Order domain events sau này |
| `nexus.auction.events.v1` | `auction-service` | `auctionId` | proposed | Auction domain events sau này |
| `nexus.fulfillment.reservation.events.v1` | `fulfillment-service` | `orderId` | proposed | Reservation events sau này |
| `nexus.fulfillment.shipment.events.v1` | `fulfillment-service` | `shipmentId` | proposed | Shipment events sau này |
| `nexus.training.integration.v1` | `commerce-service -> notification-service` | request key / `aggregateId` | demo | Smoke topic đang dùng để trace producer -> consumer |

Quy ước đặt topic:

- Không tạo một topic cho mỗi table.
- Đặt topic theo domain event stream và owner service.
- Giữ suffix `.v1` để có đường migrate khi event contract thay đổi lớn.
- Dùng key ổn định theo aggregate để Kafka giữ ordering trong cùng một partition.

## Service nào có Kafka client

Các business service có `spring-boot-starter-kafka`:

- `user-service`
- `catalog-service`
- `commerce-service`
- `auction-service`
- `fulfillment-service`
- `notification-service`

Các service hạ tầng không tạo Kafka client:

- `config-service`
- `discovery-service`
- `api-gateway`

Config dùng chung nằm trong `config-service/src/main/resources/config/application.yml`:

- `spring.kafka.bootstrap-servers=localhost:29092` cho service chạy từ host.
- Override thành `kafka:9092` nếu service chạy trong Compose network.
- Producer dùng `acks=all` và `enable.idempotence=true`.
- Consumer dùng `enable-auto-commit=false`, `ack-mode=record`, `allow.auto.create.topics=false`.
- `spring.kafka.admin.auto-create=false`, nên application không tự tạo topic.

## Chuẩn bị local infra

Chạy từ repository root trên Windows CMD:

```cmd
cd be-project-nexus\infra\local-dev
if not exist .env copy .env.example .env
notepad .env
```

Điền đủ biến trong `.env`. Ví dụ local đang dùng Kafka UI port `18090`:

```dotenv
TZ=Asia/Ho_Chi_Minh
DB_HOST=localhost
DB_PORT=5433
DB_USERNAME=nexus
DB_PASSWORD=nexus-local-only
KAFKA_PORT=29092
KAFKA_UI_PORT=18090
REDIS_PORT=6379
REDIS_PASSWORD=nexus-local-only
MAILPIT_SMTP_PORT=1025
MAILPIT_UI_PORT=8025
```

Start Kafka, topic initializer, và Kafka UI bằng một lệnh thường dùng:

```cmd
docker compose --profile debug up -d
docker compose ps -a kafka kafka-init kafka-ui
```

`kafka-init` là container one-shot dùng cùng image `apache/kafka:4.3.1`. Nó đợi broker healthy, đọc `kafka/topics.conf`, tạo topic còn thiếu, rồi validate partition/RF/config. Trạng thái `Exited (0)` là bình thường: init đã xong, không phải service bị crash.

Kafka UI:

```text
http://localhost:18090
```

Nếu team dùng `KAFKA_UI_PORT` khác trong `.env`, đổi URL theo port đó.

Mỗi developer có Kafka local riêng vì Compose volume nằm trên máy của người đó. Topic config thì giống nhau vì đi theo Git trong `infra/local-dev/kafka/topics.conf`. Khi pull thay đổi topic manifest, chạy lại `docker compose --profile debug up -d`; Compose sẽ chạy init path cần thiết theo dependency mới. Nếu chỉ bấm Restart container trong Docker Desktop hoặc chạy `docker compose restart kafka`, đừng xem đó là topic migration path vì completed one-shot init không được đảm bảo chạy lại.

## Topic init tự động

`kafka-init` validate manifest trước khi mutate Kafka. Init sẽ fail nếu manifest sai format, topic bị duplicate, hoặc topic đang tồn tại nhưng lệch partition/config. Init không delete topic, không tự tăng partition, và không alter config để tránh phá dữ liệu local.

Kiểm tra log init khi cần debug:

```cmd
docker compose logs kafka-init
docker compose ps -a kafka-init
```

Nếu chỉ muốn check thủ công, wrapper PowerShell/Bash là diagnostic tool, không phải bước startup thường ngày:

```cmd
powershell -NoProfile -ExecutionPolicy Bypass -File .\kafka\provision-topics.ps1 -CheckOnly
```

```bash
./kafka/provision-topics.sh --check-only
```

Manifest `topics.conf` giữ một danh sách topic duy nhất, ví dụ format:

```text
default|partitions|4
default|replication.factor|1
config|cleanup.policy|delete
config|retention.ms|604800000
topic|nexus.training.integration.v1|commerce-service -> notification-service|request key / aggregateId|demo|Smoke producer -> consumer
```

`kafka-init` validate:

- partition count đúng `4`;
- replication factor đúng `1`;
- `cleanup.policy=delete`;
- `retention.ms=604800000`.

## Kiểm tra topic từ CLI

List topic qua listener nội bộ `kafka:9092`:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
```

Describe smoke topic:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe --topic nexus.training.integration.v1
```

Kết quả đúng phải có `PartitionCount: 4`, `ReplicationFactor: 1`, và 4 dòng partition `0..3`.

Kiểm tra listener host `localhost:29092` từ máy host:

```cmd
docker run --rm --network host apache/kafka:4.3.1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic nexus.training.integration.v1
```

Lệnh `docker run --network host` cần Docker Desktop 4.34+ và bật host networking. Nếu Docker Desktop không bật host networking, dùng CLI bên trong broker cho các bước Kafka và dùng Spring service chạy từ host để kiểm tra `localhost:29092`.

## Chạy smoke flow trong project

Mở 3 run configuration trong IntelliJ IDEA hoặc 3 terminal từ `be-project-nexus`.

| Service | Port | Profile / Program arguments | Mục đích |
|---|---:|---|---|
| `config-service` | `8888` | không cần profile | Config Server |
| `commerce-service` | `8083` | `--spring.profiles.active=kafka-smoke` | Producer smoke endpoint |
| `notification-service` | `8086` | `--spring.profiles.active=kafka-smoke` | Consumer smoke endpoint |

Trong IntelliJ: mở **Run → Edit Configurations**, đặt các argument ở cột trên vào **Program arguments** (không phải VM options). Chạy Config Server trước, đợi started, rồi chạy notification và commerce.

Nếu muốn chạy bằng terminal:

Terminal 1 - Config Server:

```cmd
cd config-service
mvnw.cmd spring-boot:run
```

Terminal 2 - producer `commerce-service` với profile `kafka-smoke`:

```cmd
cd commerce-service
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=kafka-smoke
```

Terminal 3 - consumer `notification-service` với profile `kafka-smoke`:

```cmd
cd notification-service
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=kafka-smoke
```

Profile `kafka-smoke` bind service vào `127.0.0.1`, tắt Eureka, tắt database auto-config, nhưng vẫn cần Config Server vì các service import config từ `http://localhost:8888`. Có thể gọi HTTP bằng `curl.exe` như bên dưới hoặc dùng Postman với cùng URL/body.

## Produce từ commerce-service

Gửi message bằng `curl.exe` trên Windows CMD:

```cmd
curl.exe -i -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"demo-order-001\",\"message\":\"hello-kafka\"}"
```

Response thành công là HTTP `201` và JSON dạng:

```json
{
  "eventId": "<uuid>",
  "key": "demo-order-001",
  "topic": "nexus.training.integration.v1",
  "partition": 0,
  "offset": 0
}
```

`partition` và `offset` thay đổi theo state Kafka local. Lưu `eventId` để search trên Kafka UI.

Request rule:

- `key` bắt đầu bằng chữ hoặc số, tối đa 100 ký tự, cho phép `.`, `_`, `:`, `-` sau ký tự đầu.
- `message` không rỗng, tối đa 2000 ký tự.

Producer tạo envelope JSON `TrainingEvent` gồm `eventId`, `eventType`, `eventVersion`, `producer`, `aggregateType`, `aggregateId`, `occurredAt`, `traceId`, và `payload.message`.

## Consume từ notification-service

Đọc 100 message gần nhất mà `notification-service` nhận trong memory:

```cmd
curl.exe http://localhost:8086/internal/kafka-smoke/messages
```

Mỗi item có metadata Kafka:

```json
{
  "eventId": "<uuid>",
  "key": "demo-order-001",
  "topic": "nexus.training.integration.v1",
  "partition": 0,
  "offset": 0,
  "receivedAt": "<instant>",
  "value": "{...raw event json...}"
}
```

Log cần thấy ở `commerce-service`:

```text
KAFKA_SMOKE_PUBLISHED eventId=... key=... topic=nexus.training.integration.v1 partition=... offset=...
```

Log cần thấy ở `notification-service`:

```text
KAFKA_SMOKE_RECEIVED eventId=... key=... topic=nexus.training.integration.v1 partition=... offset=... clientId=notification-smoke
```

## Track trên Kafka UI

Mở `http://localhost:18090` rồi kiểm tra:

1. **Topics** -> `nexus.training.integration.v1`.
2. Tab **Messages**.
3. Search bằng `eventId` hoặc `demo-order-001`.
4. Xem `partition`, `offset`, `key`, và raw JSON value.
5. Vào **Consumers** -> `nexus.notification.kafka-smoke.v1`.
6. Xem members, committed offset và lag.

Kafka UI không thay thế distributed tracing. Trong scope này, UI chỉ chứng minh record đã vào topic và consumer group đã commit offset.

## Xem partition và consumer group bằng CLI

Describe group:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group nexus.notification.kafka-smoke.v1
```

Xem member assignment:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group nexus.notification.kafka-smoke.v1 --members --verbose
```

Xem end offsets của topic:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic nexus.training.integration.v1
```

## Chạy 2 consumer cùng group để thấy chia partition

Giữ instance `notification-service` đầu tiên ở port `8086`. Start instance thứ hai ở port `8087`, cùng `group-id`, khác `client-id`:

```cmd
cd notification-service
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=kafka-smoke -Dspring-boot.run.arguments="--server.port=8087 --nexus.kafka.smoke.client-id=notification-smoke-2"
```

Kiểm tra group membership:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group nexus.notification.kafka-smoke.v1 --members --verbose
```

Với topic 4 partitions và 2 instances cùng group, Kafka assign mỗi partition cho đúng 1 instance trong group. Phân bổ thường là 2/2, nhưng thứ tự partition thuộc instance nào do group coordinator quyết định.

Produce nhiều key khác nhau để record đi qua nhiều partition:

```cmd
curl.exe -s -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"demo-order-001\",\"message\":\"message-001\"}"
curl.exe -s -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"demo-order-002\",\"message\":\"message-002\"}"
curl.exe -s -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"demo-order-003\",\"message\":\"message-003\"}"
curl.exe -s -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"demo-order-004\",\"message\":\"message-004\"}"
```

Không dùng một key duy nhất nếu mục tiêu là nhìn partition spread, vì cùng key sẽ giữ ordering và thường đi vào cùng partition.

Dừng instance port `8087` bằng `Ctrl+C`. Đây là graceful stop nên dùng cho bài test rebalance, không dùng cho bài test redelivery. Chạy lại lệnh `--members --verbose`: partition của instance đã dừng sẽ move về instance còn lại sau rebalance.

## Redelivery khi consumer chết trước commit

Spring Kafka trong config chung dùng `enable-auto-commit=false` và `ack-mode=record`. Listener commit offset sau khi method xử lý record return thành công. Nếu process chết trước khi listener return, committed offset chưa tăng, nên khi consumer restart cùng group, Kafka giao lại record cùng partition/offset.

Trước khi test, dừng toàn bộ `notification-service` đang chạy trong group `nexus.notification.kafka-smoke.v1`. Nếu còn một instance bình thường trong cùng group, Kafka có thể assign record cho instance đó và bài test không chứng minh được redelivery.

Start duy nhất một `notification-service` với delay 60 giây để có thời gian kill trước commit:

```cmd
cd notification-service
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=kafka-smoke -Dspring-boot.run.arguments="--nexus.kafka.smoke.processing-delay-ms=60000"
```

Produce một message mới:

```cmd
curl.exe -i -X POST http://localhost:8083/internal/kafka-smoke/messages -H "Content-Type: application/json" -d "{\"key\":\"redelivery-demo-001\",\"message\":\"kill-before-commit\"}"
```

Khi log notification-service in `KAFKA_SMOKE_RECEIVED` rồi tới dòng delay trước khi listener return, ghi lại `partition` và `offset`, sau đó kiểm tra group offset ở terminal khác:

```text
KAFKA_SMOKE_DELAY_BEFORE_RETURN delayMs=60000 key=redelivery-demo-001 topic=nexus.training.integration.v1 partition=... offset=... clientId=notification-smoke
```

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group nexus.notification.kafka-smoke.v1
```

`CURRENT-OFFSET` vẫn chưa nhảy qua record đang delay. Kill JVM của `notification-service` trước khi hết 60 giây. Không dùng `Ctrl+C` ở bước này vì graceful shutdown có thể chờ listener kết thúc rồi commit offset.

Trên Windows, tìm PID JVM của app `notification-service` bằng `jps -l` hoặc log startup, rồi kill đúng JVM đó. Không kill broker Kafka và không chọn nhầm Maven wrapper nếu `jps` hiển thị nhiều process:

```cmd
jps -l
taskkill /PID <PID> /F
```

Start lại `notification-service` cùng group:

```cmd
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=kafka-smoke
```

Kết quả đúng: log `KAFKA_SMOKE_RECEIVED` in lại cùng `topic`, `partition`, `offset` của record trước đó. Đây là redelivery của cùng record, không phải producer gửi bản copy thứ hai. Không reset group offset và không dùng `--from-beginning` cho bước restart này.

Sau khi listener return thành công, chạy lại group describe. `CURRENT-OFFSET` của partition đó phải tăng lên `offset + 1`.

## Kiểm tra setup tự động (2026-10-03)

Đã chạy trên một Compose stack/volume **cách ly** (Kafka host `39092`, UI `38090`), không reset dữ liệu Kafka/Postgres đang có trên máy:

- Lệnh Compose từ Kafka mới tạo đúng 7 topic trong manifest, mỗi topic 4 partition/RF 1, cấu hình `cleanup.policy=delete`, `retention.ms=604800000`; `kafka-init` kết thúc `Exited (0)` trước khi UI khởi động.
- Chạy lại `docker compose up -d` giữ nguyên message đã gửi (`qa-preserved`). Chỉnh manifest **chỉ trong stack test** rồi chạy lại đã tạo được thêm đúng một topic, không xóa topic cũ.
- Thử cố tình để manifest yêu cầu 3 partition trong stack test: init báo lệch `partitions=4 expected=3`, Compose thất bại và UI mới không khởi động. Khôi phục manifest hợp lệ: Compose lên lại, message cũ vẫn còn.
- `commerce-service` POST event `90beb8bd-fee0-41d5-ac68-09506037ec52` key `compose-auto-001` vào partition `0`, offset `0`; `notification-service` GET thấy đúng cùng metadata. Kafka UI API báo cluster KRaft online và topic có 4 partition.
- Fake Kafka CLI regression, Bash syntax, PowerShell parse, Bash/PowerShell 7.5 `-CheckOnly` wrapper và Maven tests của commerce/notification đều pass. Chưa chạy native Windows PowerShell 5.1 ở môi trường Linux này.

Topic `nexus.training.qa.new.v1` trong bài test thêm topic là topic QA tạm ở stack cách ly, **không nằm trong manifest của project**.

## Verification đã chạy trong branch này

Bảng dưới ghi lại bài test từ commit trước khi chuyển sang `kafka-init`; kết quả của setup tự động mới nằm ở mục ngay trên.

Ngày verify: 2026-10-03. Môi trường verify là Linux với Java 25 compile target release 21, Docker Compose đang chạy `apache/kafka:4.3.1` healthy. Windows CMD/PowerShell command ở trên là command team dùng trên máy Windows; riêng PowerShell đã được verify bằng PowerShell 7.5.4, chưa verify native Windows PowerShell 5.1 trong môi trường này.

| Hạng mục | Kết quả | Evidence |
|---|---|---|
| Kafka local | Historical pass | `docker compose ps` showed `nexus-kafka` healthy, no ZooKeeper container |
| Topic provision Bash | Historical pass | Manual wrapper passed before `kafka-init` automation |
| Topic check PowerShell | Historical pass on PS 7.5.4 | Manual check wrapper passed; not required for normal startup |
| Topic shape | Historical pass | `nexus.training.integration.v1` had 4 partitions, RF 1, delete cleanup, 7-day retention |
| Producer -> consumer smoke | Pass | POST produced `eventId=ab511243-8eed-4507-8fcf-577aa86c1bd8`, `key=root-e2e-001`, partition `1`, offset `0`; notification GET returned the same metadata |
| Kafka UI message view | Pass | Browser verified raw event JSON, key, partition, and offset in Kafka UI Messages |
| Two consumers same group | Pass | With notification instances on ports `8086` and `8087`, 12 spread events split as 7 records on partitions `0,1` and 5 records on partitions `2,3` |
| Rebalance after stopping one consumer | Pass | After stopping port `8087`, remaining port `8086` owned all 4 partitions |
| Maven verify | Pass | All 6 business services passed `mvn verify`; commerce had 5 tests, notification had 6 tests, config had 1 test |
| Fake Docker regression | Pass | Regression suite passed |
| Redelivery kill-before-commit | Pass | Delayed consumer `120000ms` received `eventId=79a24d26-fa6d-4771-ac92-2064ac0f14a8`, `key=root-crash-001`, partition `2`, offset `4`; group `CURRENT-OFFSET` was `4` before `SIGKILL`; restart with the same group, no reset, and no `--from-beginning` returned the same event/partition/offset; sau xử lý, `CURRENT-OFFSET=5` và lag `0` |

## Điều hiện tại chưa phải production flow

- Domain topics đang được provision trước để team thống nhất contract, chưa có handler business thật.
- Smoke endpoint là endpoint nội bộ theo profile `kafka-smoke`, không expose qua API Gateway.
- Smoke consumer lưu 100 message trong memory để quan sát nhanh, restart service là mất danh sách này.
- Invalid JSON vẫn có thể được lưu raw với `eventId=null` trong smoke demo; chưa có DLT.
- `commerce-service/src/main/resources/db/migration/V1__init.sql` đã có migration cho `orders`, `outbox`, và `processed_events`, nhưng profile `kafka-smoke` cố ý tắt database. Flow smoke hiện tại chưa implement outbox publisher, transactional boundary, database side effect, hoặc `processed_events` dedup runtime.
- `enable.idempotence=true` chỉ chống duplicate do producer retry trong cùng producer session; không chống double-click HTTP, không chống app gọi `send()` hai lần, không chống consumer redelivery.

## Checklist cho developer

Mỗi backend developer nên chạy ít nhất một lần:

1. `docker compose --profile debug up -d`.
2. Confirm `kafka-init` `Exited (0)` bằng `docker compose ps -a kafka-init` hoặc `docker compose logs kafka-init`.
3. Confirm `nexus.training.integration.v1` có 4 partitions trên CLI hoặc Kafka UI.
4. Start Config Server không profile; `commerce-service` và `notification-service` với profile `kafka-smoke`.
5. POST một message vào `commerce-service`.
6. GET message từ `notification-service`.
7. Search cùng `eventId` trên Kafka UI.
8. Start 2 notification instances cùng group và xem partition assignment.
9. Kill một consumer trước commit và thấy redelivery cùng partition/offset.

Không đánh dấu thay người khác. Mỗi người tự ghi event ID của lần chạy của mình:

| Developer | Date | Event ID | Kafka UI checked | 2-consumer split checked | Redelivery checked |
|---|---|---|---|---|---|
| Pending | Pending | Pending | Pending | Pending | Pending |
| Pending | Pending | Pending | Pending | Pending | Pending |
| Pending | Pending | Pending | Pending | Pending | Pending |
