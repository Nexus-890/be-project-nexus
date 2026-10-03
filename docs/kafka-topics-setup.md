# Kafka topics setup trong project Nexus

Tài liệu này dùng sau khi đã đọc `docs/kafka-kraft.md`. File KRaft giải thích Kafka 4.x chạy như thế nào; file này giải thích cách project Nexus khai báo topic, chia partition, chạy hai kiểu demo producer -> consumer, và xem kết quả trên Kafka UI.

## Chạy nhanh trên máy mỗi developer

1. Điền `infra/local-dev/.env` một lần (xem ví dụ ở mục **Chuẩn bị local infra**).
2. Trong `be-project-nexus\infra\local-dev`, chạy `docker compose --profile debug up -d`. Compose tự tạo 7 topic, mỗi topic 4 partition; **không cần chạy `.ps1`**.
3. Chọn một trong hai đường demo: **normal + PostgreSQL** ở mục [Demo với DB](#demo-giữa-hai-service-chạy-bình-thường-với-postgresql), hoặc **`kafka-smoke`** ở mục [Smoke flow](#chạy-smoke-flow-trong-project) để kiểm tra Kafka nhanh mà không cần DB/Eureka.
4. POST message từ Commerce rồi GET ở Notification theo endpoint của đường demo đã chọn.
5. Mở `http://localhost:18090` → **Topics** → `nexus.training.integration.v1` → **Messages** để xem cùng `eventId`, partition, offset.

Đây là Kafka riêng của từng máy, nhưng cả team dùng cùng định nghĩa topic/partition trong Git. Các lệnh chi tiết và cách kiểm tra nằm bên dưới.

## Phạm vi hiện tại

- Kafka chạy bằng `infra/local-dev/compose.yaml`, không có ZooKeeper.
- Topic contract local nằm ở `infra/local-dev/kafka/topics.conf`.
- Compose tự chạy one-shot service `kafka-init` để tạo/validate topic từ `topics.conf`; developer không cần chạy script hằng ngày.
- Sáu business topic đang là scaffold để thống nhất tên topic và partition trước khi viết handler domain thật.
- Hai flow demo độc lập cùng dùng topic `nexus.training.integration.v1`:
  - producer: `commerce-service`
  - consumer: `notification-service`
- **Normal/default profile**: kết nối PostgreSQL, lưu bản ghi demo ở `commerce_db.outbox`, gửi Kafka, consumer ghi `notification_db.event_logs` và chống xử lý trùng bằng `processed_events`.
- **`kafka-smoke` profile**: vẫn giữ để demo transport nhanh. Nó tắt database auto-config và Eureka client, dùng bộ nhớ tạm để xem message. Không dùng profile này để kiểm chứng persistence.
- Normal demo hiện gửi Kafka đồng bộ trong request, **chưa phải transactional outbox publisher có retry**; không dùng làm mẫu đảm bảo atomic DB/Kafka trong production.

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

## Demo giữa hai service chạy bình thường với PostgreSQL

Đây là đường demo **không bật profile `kafka-smoke`**. Commerce và Notification dùng database riêng, Flyway chạy `db/migration/V1__init.sql` khi kết nối DB mới; Config Server và Eureka chạy như cấu hình thông thường. Endpoint `/internal/kafka-demo/*` chỉ là demo nội bộ, chưa phải API tạo đơn hàng thật.

### 1. Chạy infra và cấu hình IntelliJ IDEA

Từ `be-project-nexus\infra\local-dev`, chạy `docker compose --profile debug up -d`, đợi `postgres`/`kafka` healthy và `kafka-init` `Exited (0)`. Chạy các Run Configuration theo thứ tự:

| Run Configuration | Port | Active profiles / Program arguments | Environment variables |
|---|---:|---|---|
| `config-service` | 8888 | giữ mặc định | không cần DB |
| `discovery-service` | 8761 | để trống | không cần DB |
| `notification-service` | 8086 | **để trống**, không dùng `kafka-smoke` | `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD` |
| `commerce-service` | 8083 | **để trống**, không dùng `kafka-smoke` | `DB_HOST`, `DB_PORT`, `DB_USERNAME`, `DB_PASSWORD` |

Trong IntelliJ: **Run → Edit Configurations → chọn service → Environment variables**. Hai service Java chạy trên host nên `DB_HOST=localhost`, `DB_PORT` là **port host** trong `infra/local-dev/.env` (ví dụ `5433`), và `DB_USERNAME`/`DB_PASSWORD` phải khớp Postgres Docker. File `.env` của Compose **không tự truyền** vào Run Configuration. Đừng commit password. Config Server cấp URL riêng: `commerce_db` cho Commerce, `notification_db` cho Notification. Flyway tự tạo các bảng trên DB rỗng; nếu DB đã có bảng tạo thủ công nhưng chưa có `flyway_schema_history`, cần kiểm tra migration trạng thái trước, không xóa volume tùy tiện.

Nếu chạy bằng terminal trong PowerShell, có thể đặt biến cho **từng terminal** trước `mvnw.cmd spring-boot:run`:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5433' # thay bằng DB_PORT trong .env của máy bạn
$env:DB_USERNAME = 'nexus' # thay bằng DB_USERNAME trong .env
$env:DB_PASSWORD = 'nexus-local-only' # thay bằng DB_PASSWORD trong .env
```

Không thêm `-Dspring-boot.run.profiles=kafka-smoke` cho đường này.

### 2. Gửi, nhận, kiểm tra DB và Kafka UI

Gửi event từ Commerce bằng CMD hoặc PowerShell:

```cmd
curl.exe -i -X POST http://localhost:8083/internal/kafka-demo/messages -H "Content-Type: application/json" -d "{\"key\":\"order-demo-001\",\"message\":\"hello-from-normal-services\"}"
```

Response `201` có `outboxId`, `eventId`, `topic`, `partition`, `offset`. Ghi lại `eventId` rồi xem Notification đã lưu vào DB:

```cmd
curl.exe http://localhost:8086/internal/kafka-demo/messages
```

GET trả các record gần nhất lấy từ `notification_db.event_logs`, **không phải danh sách memory**. Dùng IntelliJ Database tool hoặc `psql` kiểm tra trực tiếp:

```sql
-- commerce_db
SELECT id, topic, published_at FROM outbox ORDER BY occurred_at DESC LIMIT 5;
-- notification_db
SELECT event_id, event_type, received_at FROM event_logs ORDER BY received_at DESC LIMIT 5;
SELECT event_id, consumer FROM processed_events ORDER BY handled_at DESC LIMIT 5;
```

Mở `http://localhost:18090` (hoặc `KAFKA_UI_PORT` của máy bạn) → **Topics** → `nexus.training.integration.v1` → **Messages**, tìm `eventId` và đối chiếu key/partition/offset với POST/GET. Vào **Consumers** → `nexus.notification.kafka-demo.v1` để xem member và committed offset. Consumer ghi `event_logs` + `processed_events` trong một DB transaction; khi nhận lại cùng `eventId`, nó bỏ qua DB side effect đã làm nhưng Kafka vẫn có thể giao lại record.

Giới hạn: producer hiện insert `outbox`, gửi Kafka đồng bộ và đánh dấu `published_at` trong request. Đây là **DB-backed integration demo**, chưa có outbox worker/retry bảo đảm eventual delivery hay atomicity giữa Postgres và Kafka; Kafka send thành công nhưng DB commit lỗi vẫn có thể lệch state. Sáu business topic khác chưa có handler nghiệp vụ thật.

### 3. Xem group và redelivery trong normal mode (tùy chọn)

Để xem hai instance chia 4 partitions, giữ Notification port `8086`, chạy thêm Notification trong IntelliJ với **cùng biến DB**, không active profile, thêm **Program arguments**:

```text
--server.port=8087 --nexus.kafka.demo.client-id=notification-demo-2
```

Từ `infra/local-dev`, xem assignments; hai instance trong cùng `group-id` chia partitions, thường là 2/2. Dừng instance `8087`, chạy lại command và đợi `8086` nhận cả 4 partitions:

```cmd
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group nexus.notification.kafka-demo.v1 --members --verbose
```

Để thử **kill trước commit**, dừng các Notification khác trong group; chạy duy nhất một Notification với `--nexus.kafka.demo.processing-delay-ms=60000`, POST một key mới, chờ log `KAFKA_DEMO_DELAY_BEFORE_RETURN`, rồi kill đúng JVM trước 60 giây (`jps -l`, `taskkill /PID <PID> /F`). Khởi động lại Notification **cùng group**, không reset offset: log phải hiện lại cùng topic/partition/offset với `stored=false` nếu DB đã ghi ở lần đầu. `stored=false` là dedup bằng `processed_events`, **không phải Kafka không redeliver**. Bài CLI độc lập và command đầy đủ để kiểm chứng offset nằm trong [`kafka-kraft.md`](kafka-kraft.md#7-offsets-commits-and-redelivery).

## Chạy smoke flow trong project

Đây là đường demo **tùy chọn, vẫn được giữ nguyên**. Chọn đường này khi chỉ muốn kiểm tra Kafka mà không cần PostgreSQL/Eureka; nó không chứng minh DB persistence. Không chạy normal và smoke instance cùng port một lúc.

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

### Kiểm tra thêm normal-mode + DB trên nhánh hiện tại (2026-10-03)

- Đã chạy `config-service` port `8888`, `discovery-service` port `8761`, Commerce port `8083` và Notification port `8086` **không bật `kafka-smoke`**, với PostgreSQL Docker ở host port `55432` của máy kiểm tra; cả hai service lấy config từ Config Server và kết nối DB riêng.
- Flyway chạy V1 trên `commerce_db` rỗng; Notification xác nhận V1 đã có. Đây là dữ liệu local của máy kiểm tra, **không phải yêu cầu mọi dev dùng port `55432`**.
- POST `/internal/kafka-demo/messages` key `normal-db-demo-20261003` trả HTTP `201`, `eventId=cece6c86-5de9-49fe-947b-788cbf54e37c`, topic `nexus.training.integration.v1`, partition `0`, offset `5`. GET `/internal/kafka-demo/messages` trả cùng event ID/key/partition/offset và payload.
- Query `commerce_db.outbox` thấy row cùng ID và `published_at` khác NULL; query `notification_db.event_logs` thấy `KAFKA_DEMO_RECEIVED` cùng ID; query `notification_db.processed_events` thấy consumer `notification-service.kafka-demo` cùng ID.
- Đã chạy lại Commerce/Notification với `kafka-smoke` trên port phụ `18083`/`18086` **không cấp biến DB**. POST smoke key `retained-smoke-verify-20261003` trả HTTP `201`, event ID `76a913ae-8001-4650-aa2d-012cddb922d0`, partition `3`, offset `3`; GET smoke trả cùng record. Endpoint `/internal/kafka-demo/messages` trong instance smoke trả `404`, chứng minh hai đường demo tách nhau. Một lần POST trước đó thất bại khi broker đang restart/health `starting`; sau khi broker `healthy`, retry thành công. Không coi lỗi lúc broker chưa sẵn sàng là bằng chứng smoke flow pass.
- Host CLI tạo `nexus-kraft-host-verify-20261003` qua `localhost:29092`, internal CLI describe được hai partitions qua `kafka:9092`; tạo `nexus-kraft-internal-verify-20261003` trong Compose rồi host CLI describe được hai partitions. Produce/consume chéo hai listener nhận `host-to-internal-20261003` ở partition `1` offset `0` và `internal-to-host-20261003` ở partition `0` offset `0`.
- Bài chia group và kill-before-commit CLI có lệnh tái hiện ở [`kafka-kraft.md`](kafka-kraft.md); evidence của lần kiểm tra trước được ghi ở bảng bên dưới. Không lấy hai bài đó từ normal DB demo để thay thế bằng chứng CLI của issue.

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
- Normal demo dùng database thật và `processed_events` ở Notification, nhưng vẫn chưa phải order business flow; `TrainingEvent` không tạo một đơn hàng trong `orders`.
- Commerce normal demo chưa có outbox worker/retry và không có atomic commit giữa PostgreSQL với Kafka. Không suy ra đảm bảo exactly-once end-to-end từ row `outbox` hoặc `enable.idempotence=true`.
- `enable.idempotence=true` chỉ chống duplicate do producer retry trong cùng producer session; không chống double-click HTTP, không chống app gọi `send()` hai lần, không chống consumer redelivery.

## Checklist cho developer

Mỗi backend developer nên chạy ít nhất một lần:

1. `docker compose --profile debug up -d`.
2. Confirm `kafka-init` `Exited (0)` bằng `docker compose ps -a kafka-init` hoặc `docker compose logs kafka-init`.
3. Confirm `nexus.training.integration.v1` có 4 partitions trên CLI hoặc Kafka UI.
4. Chọn **normal + DB** (Config, Discovery, Commerce, Notification không `kafka-smoke`) hoặc **smoke** (Config, Commerce, Notification với `kafka-smoke`).
5. POST một message vào `commerce-service` bằng endpoint tương ứng.
6. GET message từ `notification-service`; với normal demo, kiểm tra thêm `outbox`, `event_logs`, `processed_events` trong PostgreSQL.
7. Search cùng `eventId` trên Kafka UI.
8. Start 2 notification instances cùng group và xem partition assignment.
9. Kill một consumer trước commit và thấy redelivery cùng partition/offset.

Không đánh dấu thay người khác. Mỗi người tự ghi event ID của lần chạy của mình:

| Developer | Date | Event ID | Kafka UI checked | 2-consumer split checked | Redelivery checked |
|---|---|---|---|---|---|
| Pending | Pending | Pending | Pending | Pending | Pending |
| Pending | Pending | Pending | Pending | Pending | Pending |
| Pending | Pending | Pending | Pending | Pending | Pending |
