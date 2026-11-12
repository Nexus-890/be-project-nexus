# Shipment của đơn đã thanh toán — #202

## Phạm vi và contract

`shipment-service` nhận `OrderPaid` trên `nexus.commerce.events`, dùng
`IdempotentConsumer` với `processed_events` trong `shipment_db`, và ghi
`ShipmentCreated` vào outbox trên `nexus.shipment.events`. Mỗi seller order có
một shipment. Envelope của `ShipmentCreated` dùng `aggregateId=shipmentId`, còn
Kafka key là `orderId`, theo [event catalogue](../api/events.md).

`ShipmentOutboxConfiguration` cung cấp `OutboxEventKeyResolver` cho relay dùng
chung trên `develop`. #202 không sửa `common-outbox` hoặc tạo relay riêng.

Consumer commit shipment `PENDING_TRACKING`, history, outbox và processed
marker trong cùng transaction của shipment-service, trước mọi cuộc gọi
carrier. Shipment giữ snapshot và lựa chọn carrier/service; chính `shipment.id`
là request identity gửi carrier. Sau commit, consumer thử lấy tracking; job
retry xử lý khi process dừng hoặc carrier chưa phản hồi. Retry sau timeout hay
rollback cập nhật tracking vẫn dùng cùng shipment đã lưu. Carrier phải thực
thi idempotency theo identity đó; tính duy nhất trong database không tự đảm
bảo exactly-once tại hệ thống carrier.

`ShipmentCreated` ghi nhận thời điểm tạo shipment: payload có
`status=PENDING_TRACKING`, `trackingCode=null`, kể cả khi fake bổ sung tracking
ngay sau transaction tạo. GET phản ánh trạng thái hiện tại; event đã lưu không
bị sửa để giống trạng thái mới.

Không có bảng hỗ trợ creation request hoặc creation key. Schema dùng bốn bảng
sẵn có `shipments`, `shipment_history`, `outbox`, `processed_events`. Migration
V2 bổ sung `shipments.idempotency_fingerprint` để đối chiếu nội dung manual
request; key nằm trong `shipments.idempotency_key` vốn đã có unique constraint.

Service điều phối nghiệp vụ và transaction, không gọi JDBC trực tiếp. Khóa và
truy vấn shipment nằm trong `shipping.repository.ShipmentRepository`; history
được ghi qua JPA `ShipmentHistoryRepository`. Consumer và publisher nằm trong
`shipping.event`, payload trong `shipping.event.payload`, job trong
`shipping.job`. Kết quả tạo nội bộ là nested record
`ShipmentCreationService.CreationResult`.

Carrier tạm lỗi, adapter chưa hỗ trợ tạo tracking, hoặc snapshot chưa có số đo
kiện/địa chỉ lấy hàng giữ `PENDING_TRACKING` và `trackingCode=null`. Job retry
chỉ bổ sung tracking cho shipment hiện có; không phát `ShipmentCreated` lần hai.
Không tự tạo số đo, địa chỉ hoặc số điện thoại để gọi carrier. Fake cấp mã ổn
định cho cùng request identity; GHN create/tracking thật không thuộc #202.
Tracking retry xử lý carrier phục hồi, không tự cung cấp parcel/pickup còn
thiếu. #202 chưa có endpoint hoàn tất dữ liệu chuẩn bị: snapshot có hai trường
này null vẫn chờ chuẩn bị. Publisher cần cung cấp snapshot chuẩn bị đầy đủ và
tin cậy để tạo tracking thành công; thay đổi snapshot đã tiếp nhận cần contract
correction/backfill được thống nhất riêng.

Các HTTP operation nằm trong [OpenAPI](../api/openapi/shipment-service.yaml):

| API | Quyền | Kết quả |
| --- | --- | --- |
| `POST /api/v1/shipments` | `SHIPPING.CREATE_SHIPMENT` và vai trò ADMIN hoặc SUPPORT_STAFF | 201, `Location`, `ApiResponse<ShipmentResponse>` |
| `GET /api/v1/shipments/{id}` | `SHIPPING.VIEW_SHIPMENT` | 200, một shipment nhìn thấy được |
| `GET /api/v1/shipments` | `SHIPPING.VIEW_SHIPMENT` | 200, array `data` và `page` cùng cấp |

Buyer chỉ đọc được shipment có `buyerId` bằng `sub` JWT của mình. ADMIN và
SUPPORT_STAFF có thể đọc mọi shipment khi có quyền đọc. Chi tiết của buyer khác
trả 404 `SHIPMENT_NOT_FOUND`; danh sách lọc quyền sở hữu trước khi phân trang và
đếm tổng. Không dùng tham số client gửi để quyết định buyer.
Manual POST chỉ dành cho ADMIN hoặc SUPPORT_STAFF có privilege tạo; buyer
được gán `SHIPPING.CREATE_SHIPMENT` nhưng thiếu vai trò vận hành vẫn nhận 403,
không thể dùng POST để đọc shipment của buyer khác.

Manual POST nhận `{orderId, warehouseId?, carrier?, serviceType?}` và bắt buộc
`Idempotency-Key` không rỗng, tối đa 100 ký tự. Khi shipment chưa có, service đọc
snapshot từ commerce qua `GET /internal/v1/orders/{id}/shipping-snapshot`, dùng
bearer của staff caller. Commerce kiểm tra độc lập privilege/vai trò và chỉ
trả đơn `PAID` có `paidAt`, buyer, fee, recipient và items đã lưu. Shipment
không đọc `commerce_db` và không nhận buyer/address/fee từ caller. Không cần
`OrderPaid` đã được giao tới consumer để manual POST tạo shipment mới.
Đơn không tồn tại trả 404 `SHIPMENT_ORDER_NOT_FOUND`; đơn chưa PAID trả 409
`SHIPMENT_ORDER_NOT_PAID`; snapshot sai trả 502 `SHIPMENT_INVALID_ORDER_SNAPSHOT`;
commerce timeout/circuit mở/lỗi kết nối trả 503 `SHIPMENT_COMMERCE_UNAVAILABLE`.
Các lỗi này xảy ra trước khi ghi shipment/history/outbox.

Manual POST dùng cùng khóa order, unique `shipments.order_id` và transaction
ghi history/outbox như consumer. Event `OrderPaid` đến trong hoặc sau manual
creation không tạo shipment hoặc creation event thứ hai. Snapshot đọc từ
commerce phản ánh trạng thái lúc đọc; không có transaction liên database hoặc
cam kết về thứ tự cancellation giữa hai service.

Cùng khóa/cùng yêu cầu trả lại shipment; tái dùng khóa với yêu cầu khác trả 409
`SHIPMENT_IDEMPOTENCY_CONFLICT`. Mỗi shipment giữ một manual key chuẩn: key đầu
tiên được lưu cùng fingerprint request khi tạo mới hoặc gắn lên shipment đã
tạo từ event; một key khác cho shipment đã có key trả 409. Replay hợp lệ dùng
shipment có sẵn, không cần gọi lại commerce. Không thêm bảng lưu key riêng.

Các trường tùy chọn chọn dispatch cho shipment mới: `warehouseId` lấy từ
request, kho tin cậy của snapshot hoặc kho mặc định; `carrier`/`serviceType`
lấy từ request hoặc lựa chọn tin cậy/cấu hình. Carrier chưa đăng ký trả 400
`SHIPMENT_INVALID_PARAMETER`; dịch vụ không hỗ trợ trả 422 `SHIPMENT_NO_SERVICE`.
Kho override cần pickup tin cậy được cấu hình riêng; thiếu kho/pickup cần chọn
trả 404 `SHIPMENT_WAREHOUSE_NOT_FOUND`. Không lấy pickup của kho cũ cho kho mới
và không biến tên ward/city legacy thành mã địa giới GHN. Địa chỉ legacy có
recipient và line1 nhưng thiếu mã, parcel hoặc pickup chỉ tạo shipment pending
chuẩn bị; retry không tự bổ sung dữ liệu đó.

Nếu shipment đã tồn tại, các lựa chọn gửi phải khớp giá trị đã lưu; khác trả
409 `SHIPMENT_IDEMPOTENCY_CONFLICT`. Carrier/service/kho không đổi sau khi
request identity đã được commit, kể cả khi tracking update rollback.

List mặc định `page=0&size=20`, size từ 1 đến 100; bộ lọc tùy chọn `orderId`
và `status`. Kết quả sắp theo thời điểm tạo giảm dần và id. JSON 2xx luôn dùng
`{code,message,data}`; list có thêm `page`. Lỗi của shipment-service dùng
`application/problem+json` và mã `SHIPMENT_*`. Lỗi gateway phát sinh trước khi
chuyển tiếp vẫn dùng enum của gateway theo contract chung.

## Chạy với event hand-publish

Khởi động PostgreSQL 18, Kafka/topic init, config-service, discovery-service và
shipment-service theo [README](../../../README.md). Cấu hình
`SHIPPING_CARRIER=FAKE` trong môi trường local/test. Không tự chuyển FAKE khi
carrier thật lỗi. Build lại `lib` trước shipment-service khi thư viện chung đổi.

Consumer bật mặc định với `nexus.shipping.order-paid-consumer-enabled=true`;
có thể tắt khi chưa hoàn tất backfill/contract của publisher. Tracking retry
poll theo `nexus.shipping.tracking-retry-poll-ms` (mặc định 5000), backoff nền
`nexus.shipping.tracking-retry-backoff-ms` (mặc định 30000); retry dùng backoff
tăng dần tới tối đa một giờ. Snapshot chưa đủ chuẩn bị được giữ ở pending;
retry không bổ sung dữ liệu thiếu và không fabricate dữ liệu. Message sai schema được giữ retry, không đánh dấu
processed như đã thành công.

Fixture bên dưới giữ đủ các trường của schema `OrderPaid` trong event
catalogue. `P01/D01/W01` là mã tổng hợp cho fake, không phải địa chỉ hợp lệ để
booking GHN. `parcel` và `pickupAddress` là dữ liệu test được chỉ định tường
minh để fake tạo tracking; ví dụ canonical trong catalogue có hai trường này
null và phải giữ ở trạng thái chờ chuẩn bị.

Chạy PowerShell từ `be-project-nexus` với Docker local đã sẵn sàng:

```powershell
$event = @'
{
  "eventId": "00000000-0000-4000-8000-000000000202",
  "eventType": "OrderPaid",
  "aggregateId": "00000000-0000-4000-8000-000000000001",
  "occurredAt": "2026-11-11T10:00:00Z",
  "traceId": "00000000000000000000000000000202",
  "payload": {
    "orderId": "00000000-0000-4000-8000-000000000001",
    "buyerId": "00000000-0000-4000-8000-000000000002",
    "orderNumber": "NX-20261111-0001",
    "orderType": "STANDARD",
    "referenceId": null,
    "paidAt": "2026-11-11T10:00:00Z",
    "orderGroupId": "00000000-0000-4000-8000-000000000020",
    "sellerId": "00000000-0000-4000-8000-000000000004",
    "orderVersion": 1,
    "sessionId": "00000000-0000-4000-8000-000000000021",
    "instructionId": "00000000-0000-4000-8000-000000000022",
    "transactionId": "00000000-0000-4000-8000-000000000023",
    "paidAmount": "200000.50",
    "currency": "VND",
    "warehouseId": "00000000-0000-4000-8000-000000000010",
    "shippingFee": "15000.00",
    "shippingInfo": {
      "recipient": {
        "name": "Demo Recipient",
        "phone": "0900000000",
        "address": {
          "line1": "1 Demo Street",
          "wardCode": "W01",
          "districtCode": "D01",
          "provinceCode": "P01"
        }
      },
      "items": [
        {
          "skuId": "00000000-0000-4000-8000-000000000005",
          "productName": "Demo item",
          "quantity": 1
        }
      ],
      "parcel": {
        "weightGrams": 1000,
        "lengthCm": 20,
        "widthCm": 15,
        "heightCm": 10
      },
      "pickupAddress": {
        "line1": "2 Demo Street",
        "wardCode": "W01",
        "districtCode": "D01",
        "provinceCode": "P01"
      }
    }
  }
}
'@ | ConvertFrom-Json
$wire = $event | ConvertTo-Json -Depth 20 -Compress
$record = $event.payload.orderId + '|' + $wire
$record | docker exec -i nexus-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic nexus.commerce.events --property parse.key=true --property 'key.separator=|'
```

Gửi lại chính `$record` để kiểm tra trùng `eventId`. Gửi thêm event có eventId
mới và cùng payload/orderId để kiểm tra ràng buộc nghiệp vụ một shipment mỗi
order. Không sửa buyer/address của cùng một paid snapshot như một cách cập nhật
đơn; dữ liệu sửa sai cần contract correction/backfill được thống nhất riêng.

Đọc `shipment_db` bằng tài khoản local đã cấu hình; không dán mật khẩu vào tài
liệu hoặc command đã lưu. Kiểm tra các truy vấn sau sau khi consumer xử lý:

```sql
SELECT id, order_id, buyer_id, carrier, shipment_status, tracking_code,
       tracking_retry_count, next_tracking_retry_at
FROM shipments
WHERE order_id = '00000000-0000-4000-8000-000000000001';

SELECT count(*) AS shipment_count
FROM shipments
WHERE order_id = '00000000-0000-4000-8000-000000000001';

SELECT count(*) AS creation_event_count
FROM outbox
WHERE event_type = 'ShipmentCreated'
  AND payload -> 'payload' ->> 'orderId' = '00000000-0000-4000-8000-000000000001';

SELECT event_id, consumer
FROM processed_events
WHERE event_id = '00000000-0000-4000-8000-000000000202';
```

Kỳ vọng một shipment, một outbox `ShipmentCreated`, một processed marker cho
consumer shipment. Với fixture đầy đủ và fake hoạt động, shipment sau cùng có
tracking và status CREATED; payload event creation vẫn PENDING_TRACKING và
trackingCode=null. Outbox relay có thể giao lại cùng event khi process chết sau
Kafka ACK nhưng trước khi đánh dấu published; consumer hạ nguồn vẫn cần
idempotency. Không đồng nhất số message broker giao với số creation fact.

Đọc topic để kiểm tra key bằng orderId, envelope aggregateId bằng shipmentId:

```powershell
docker exec -i nexus-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic nexus.shipment.events --from-beginning --property print.key=true --timeout-ms 10000
```

Để chạy lại fixture như một order mới, thay UUID orderId ở payload và
aggregateId, đồng thời thay eventId. Giữ eventId cố định khi kiểm tra redelivery.
Không xóa dữ liệu lịch sử của môi trường dùng chung chỉ để làm lại demo.

## Kiểm tra acceptance và giới hạn

| Tình huống | Điều cần xác minh |
| --- | --- |
| Hand-publish event đủ dữ liệu, fake hoạt động | Một shipment cuối cùng CREATED có tracking và một ShipmentCreated ghi nhận creation PENDING_TRACKING |
| Cùng event giao hai lần | Một shipment, một outbox creation fact, một processed marker |
| Hai eventId cho cùng order | Một shipment, không gọi tạo carrier lần hai |
| Carrier tạm lỗi rồi phục hồi | PENDING_TRACKING được lưu; retry cùng request identity bổ sung tracking, xóa lịch retry, không thêm creation event |
| Transaction tạo shipment/outbox rollback | Không có shipment/marker/outbox, carrier chưa được gọi; event có thể xử lý lại |
| Carrier thành công nhưng transaction cập nhật tracking rollback | Shipment pending đã tồn tại; retry dùng lại shipment.id, fake trả cùng tracking |
| Parcel/pickup chưa có | PENDING_TRACKING; retry không tự bổ sung dữ liệu, #202 chưa có endpoint hoàn tất chuẩn bị |
| Buyer A đọc shipment của A/B | A được 200; B trả 404; list/totals không chứa dữ liệu của B |
| ADMIN/SUPPORT_STAFF đọc có privilege | Nhìn thấy shipment ngoài buyerId của mình |
| Buyer có CREATE privilege gọi manual POST | 403 SHIPMENT_FORBIDDEN; không gọi service tạo, không lộ shipment |
| POST trùng key/cùng body | Cùng id và Location; một shipment |
| POST trùng key/khác body | 409 SHIPMENT_IDEMPOTENCY_CONFLICT |
| POST key khác cho shipment đã gắn key | 409 SHIPMENT_IDEMPOTENCY_CONFLICT; không thay key đã lưu hoặc thêm receipt |
| POST carrier/service khác lựa chọn đã lưu | 409 SHIPMENT_IDEMPOTENCY_CONFLICT; không tạo booking bằng namespace/body khác |
| POST cho PAID order chưa có shipment/chưa nhận OrderPaid | 201; snapshot lấy từ commerce, một shipment và một outbox creation fact |
| Manual POST chạy đồng thời với OrderPaid | Một shipment, một creation event, request identity không đổi |
| POST cho order không tồn tại/chưa PAID | 404 SHIPMENT_ORDER_NOT_FOUND / 409 SHIPMENT_ORDER_NOT_PAID; không ghi shipment |
| Commerce timeout hoặc circuit mở | 503 SHIPMENT_COMMERCE_UNAVAILABLE; không ghi shipment |
| Snapshot upstream sai/mismatch orderId | 502 SHIPMENT_INVALID_ORDER_SNAPSHOT; không ghi shipment |
| POST chọn carrier/service cho shipment mới | Lựa chọn hợp lệ được lưu; carrier chưa đăng ký 400, dịch vụ không hỗ trợ 422 |
| POST override kho | Dùng pickup tin cậy của kho mới; thiếu cấu hình trả 404, không tái dùng pickup của kho cũ |
| Header/body/query sai | Problem Details, mã SHIPMENT_*, không thay đổi dữ liệu |

## Manual creation khi OrderPaid chưa được giao

Chuẩn bị một seller order thực sự ở trạng thái PAID trong commerce, có
`paidAt`, fee, recipient và items hợp lệ. Dùng orderId chưa có shipment để
kiểm chứng nhánh tạo mới; không xóa shipment đang được sử dụng để ép test.
Commerce-service và shipment-service phải chạy cùng contract snapshot mới.
Feign tìm `commerce-service` qua service discovery; khi chạy trực tiếp có thể
đặt `SHIPPING_COMMERCE_BASE_URL=http://localhost:8083`. Tổng timeout đọc snapshot
dùng `SHIPPING_API_TIMEOUT_MS` (mặc định 3000ms), không retry tự động; circuit
breaker của dependency COMMERCE dùng cùng cấu hình resilience shipping.

Staff caller có `SHIPPING.CREATE_SHIPMENT` và `ADMIN`/`SUPPORT_STAFF` gửi:

```http
POST http://localhost:8088/api/v1/shipments
Authorization: Bearer <staff-access-token>
Idempotency-Key: manual-paid-order-202
Content-Type: application/json
```

```json
{
  "orderId": "<paid-seller-order-id>",
  "carrier": "FAKE",
  "serviceType": "STANDARD"
}
```

Kỳ vọng 201 với Location và ApiResponse. Buyer/fee/recipient/items phải khớp
snapshot của commerce. Nếu snapshot có parcel/pickup/mã địa giới đầy đủ, fake
có thể cấp tracking sau commit; nếu chưa đủ, shipment vẫn pending chuẩn bị.
Gửi lại cùng body/key phải trả cùng id mà không thêm outbox. Sau đó giao
`OrderPaid` cho cùng order để kiểm tra consumer không tạo thêm shipment/event.
Các token trong ví dụ là placeholder, không lưu bearer vào evidence.

Chạy các kiểm tra bắt buộc từ `be-project-nexus`:

```powershell
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/shipment-service.yaml
```

Build/install `lib` từ cùng checkout trước khi chạy `mvnw.cmd verify` trong
`shipment-service`, để dùng API `OutboxEventKeyResolver` đã có trên `develop`.

## Kiểm thử sau thay đổi luồng tạo shipment

Ngày 12/11/2026, rebase #202 lên `develop` `b97244b9` và kiểm tra lại bằng
Java 21: shipment-service 338 test pass; commerce-service 403 test pass,
5 test catalog opt-in skip; config-service 7 test pass. Contract inventory
khớp 88 operation. OpenAPI hợp lệ; commerce giữ ba cảnh báo đường dẫn mơ hồ
có sẵn trên `develop`, không phát sinh từ #202.

Conflict hai test cùng tên được giải quyết bằng cách giữ nguyên
`OrderShippingSnapshotTest` của `develop` (4 test), đặt 14 test snapshot đơn
PAID của #202 trong `PaidOrderShippingSnapshotTest`. Cả hai bộ đều pass;
không thay đổi code checkout, đóng kiện hoặc payload OrderPaid đã merge.

Ngày 11/11/2026, kiểm tra lại bằng Java 21 trên nền `develop` `02385a14`, sau
khi bỏ commit #201 đã merge và merge commit cũ khỏi lịch sử #202:

- Shipment-service: 338 test pass, không lỗi hoặc skip; gồm 24 tình huống
  PostgreSQL, Kafka thật, HTTP client/timeout/token relay và circuit breaker.
- Commerce-service: 373 test pass, không lỗi (378 test tổng); 5 test `CatalogEnvelopeHttpTest`
  opt-in skip vì chưa đặt `CATALOG_CONTRACT_SIMULATION_URL`. Cả 23 test snapshot
  mới đều pass (14 PostgreSQL và 9 HTTP security/contract).
- Config-service: 7 test pass.
- Contract inventory khớp 86 operation; Redocly lint cả OpenAPI commerce và
  shipment, cùng `git diff --check`, đều pass.

Các ca mới xác minh manual tạo trước OrderPaid, replay không gọi lại commerce,
manual/event chạy đồng thời rồi worker xử lý pending, lựa chọn dispatch, lỗi
upstream không ghi dữ liệu, rollback outbox trước carrier và carrier phục hồi.
Schema vẫn có bốn bảng gốc (ngoài `flyway_schema_history`); V2 chỉ thêm cột fingerprint.

ArchUnit kiểm tra service không phụ thuộc JDBC/`java.sql`; các test PostgreSQL
và Kafka xác minh history qua JPA vẫn cùng transaction với shipment/outbox.

`ShipmentCreationIntegrationTest` kiểm tra trên PostgreSQL 18 thật với V1/V2
và `ddl-auto=validate`: redelivery, eventId mới cho cùng order, giao đồng thời,
carrier lỗi/phục hồi, rollback transaction tạo trước khi gọi carrier, rollback
cập nhật tracking giữ shipment identity, circuit mở không tăng số lần gọi
thực, manual idempotency và snapshot thiếu/sai. Các MVC/service test kiểm tra
ownership, phân trang và quyền manual POST.

`ShipmentKafkaIntegrationTest` dùng Kafka 4.3.1 và PostgreSQL 18 riêng,
hand-publish JSON `OrderPaid` hai lần qua producer String. Test kiểm tra một
shipment cuối cùng có tracking, một marker, một outbox creation PENDING_TRACKING
và một event trên Kafka có key `orderId`, envelope aggregateId `shipmentId`.
Test chờ offset của cả hai delivery được commit và outbox được đánh dấu published.

#201 và #156 đã merge vào `develop`; #202 dùng adapter và contract `OrderPaid`
từ nền này. Hand-publish xác minh contract consumer, chưa chứng minh chuỗi
webhook #155 → CheckoutPaymentConfirmed/OrderPaid #156 → shipment. Demo real
chain vẫn cần được chạy riêng với snapshot chuẩn bị đầy đủ, tin cậy để fake
cấp tracking; ví dụ canonical có parcel/pickup null tiếp tục pending.
GHN booking thật chưa được triển khai trong #202; quote GHN của #201 không
phải tạo vận đơn.
