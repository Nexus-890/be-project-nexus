# Event catalogue — issue #103

Phạm vi event đã được leader chốt: **13 event R1** trong danh sách bên dưới; **51 event còn lại chỉ ghi `R2`**. R2 là chưa triển khai event trong đợt này, không phải cam kết lịch release tiếp theo. Phạm vi event tách biệt với phạm vi endpoint HTTP: tính năng Auth/Product/Cart có thể thuộc R1 nhưng event tương ứng vẫn R2.

Tài liệu chuẩn bị cho MR #103. Scope R1/R2 là quyết định đã chốt; field/schema/example là thiết kế payload để hai backend lane review. Không mở rộng danh sách publisher, subscription hoặc topic ngoài yêu cầu sprint. Approval MR được ghi nhận trên GitLab, không điền sẵn trong tài liệu.

## Nguồn và quy ước

- Danh sách 13 R1 và quyết định 51 R2 do leader xác nhận, được người phụ trách #103 cung cấp ngày 2026-10-19; thay thế các phân loại suy ra trước đây từ API catalogue.
- SRS v1.0 mục 2.8: đủ 64 tên event, giữ đúng spelling `InventoryReservationExpired`.
- Issue #103: catalogue gồm payload, publisher/consumer issue và hai lane review; issue có yêu cầu publish là nguồn của 13 publisher R1.
- Toàn bộ 128 work item Sprint 0–3 đã được đối chiếu ngày 2026-10-19, cả opened và closed; các issue liên quan được link tại mỗi event. Milestone hiện là S0–S3, dù prose cũ còn gọi Sprint 1–4.
- #102: branch `fsa_VinhDP/feature/102-event-envelope-and-topic-constants`, commit `ccbdd28`, cung cấp envelope, factory, constants và shared Kafka configuration. Đây là bằng chứng source của branch, không khẳng định branch đã merge vào develop.
- Tiền đối chiếu #66/#67 và V2 migrations đã chuyển sang numeric(19,2); không làm tròn phần lẻ theo prose bigint cũ của #140/#146.

## EventEnvelope và Kafka từ #102

| Field | Java type | Quy tắc |
| --- | --- | --- |
| eventId | UUID | Non-null; factory tạo UUID; relay/redelivery giữ nguyên identity. |
| eventType | String | Non-null, không blank; đúng tên SRS. |
| aggregateId | String | Non-null, không blank; mapping domain ở từng payload. |
| occurredAt | Instant | Non-null; factory lấy từ Clock. JSON UTC ISO-8601. |
| traceId | String | Non-null, không blank; Micrometer current span. |
| payload | T | Non-null; nested field nullable chỉ khi schema cho phép. |

Factory không tạo fallback trace khi thiếu current span: publisher scheduler phải có traced context. Relay giữ eventId/occurredAt/traceId/payload đã lưu, không gọi factory tạo envelope mới khi retry. Producer dùng qualifier `EventKafka.TEMPLATE` (`eventKafkaTemplate`); listener chọn `EventKafka.LISTENER_CONTAINER_FACTORY` (`eventListenerContainerFactory`). Default String Kafka beans vẫn tồn tại; không dùng nhầm bean.

Shared serializer ghi JSON không Java type headers; listener nhận `EventEnvelope<JsonNode>` qua `ErrorHandlingDeserializer`. Consumer dispatch theo eventType rồi đọc/validate payload tương ứng. Error header không đồng nghĩa dự án đã có retry/DLT policy.

Ví dụ envelope đầy đủ cho CategoryCreated; đây là minh họa schema/type, không phải kết quả integration test đã chạy:

```json
{
  "eventId": "01936b2a-7c4e-7000-8000-000000000001",
  "eventType": "CategoryCreated",
  "aggregateId": "00000000-0000-4000-8000-000000000007",
  "occurredAt": "2026-10-19T08:00:00Z",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "payload": {
    "categoryId": "00000000-0000-4000-8000-000000000007",
    "name": "Electronics",
    "slug": "electronics",
    "parentId": null
  }
}
```

Source test #102 kiểm tra non-null/blank fields, UUID/time/current trace, JSON round trip thành JsonNode, UTC Instant, không type headers và missing trace gây deserialization error. Chưa chạy lại thành công suite Java trong phiên này vì thiếu cached Spring Boot parent 4.1.1 và không tải được Maven Central. Demo CatalogTestEvent không phải business publisher/outbox/deduplication và không thuộc 64 SRS event.

## Topic và quy tắc chung

| Owner | Constant #102 | Topic | R1 catalogue |
| --- | --- | --- | --- |
| user-service | Topics.USER | nexus.user.events | Không có event R1 trong scope đã chốt. |
| catalog-service | Topics.CATALOG | nexus.catalog.events | CategoryCreated/Updated/Deleted. |
| commerce-service | Topics.COMMERCE | nexus.commerce.events | OrderCreated/Paid/Cancelled. |
| auction-service | Topics.AUCTION | nexus.auction.events | AuctionStarted/Ended, BidPlaced, AuctionWon/Failed/Settled. |
| fulfillment-service | Topics.FULFILLMENT | nexus.fulfillment.events | InventoryReservationExpired. |

Không thêm topic notification/system. Constant USER vẫn thuộc library #102; scope publication này không xóa constant. Local topic manifest có 1 partition, 1 replica, retention 7 ngày; không suy ra cấu hình production.

- State change và outbox row chung transaction (#104). Writer không gọi Kafka, relay claim với FOR UPDATE SKIP LOCKED, send lỗi giữ unpublished. Crash sau ACK trước published_at có thể redeliver; không tuyên bố exactly-once DB/Kafka.
- Consumer dedupe theo `(eventId, consumer)` (#105), marker và business effects chung transaction. Handler throw/rollback không ghi processed thành công. Nhiều consumer khác nhau phải có consumer identity riêng.
- Kafka key được chỉ rõ từng event. Category dùng categoryId; metadata manifest catalog đang ghi productId cần đồng bộ label aggregate khi triển khai. #106 yêu cầu create/update/delete đúng thứ tự: nhiều relay cần bảo vệ thứ tự theo aggregate; ORDER BY + SKIP LOCKED đơn thuần chưa chứng minh ordering.
- Reservation expiry có aggregateId=reservationId, key=orderId. Không giả định ordering giữa commerce/fulfillment topic và không chạy lại stock mutation chỉ vì nhận event mô tả stock đã đổi.
- Draft 7; mọi property đã liệt kê là required; chỉ explicit nullable nhận null. additionalProperties=true cho additive evolution. UUID là string, timestamp UTC kết thúc Z.
- Tiền JSON number có tối đa hai chữ số thập phân (multipleOf 0.01); Java dùng BigDecimal và DB numeric(19,2) theo #66/#67/V2. Không chuyển qua float/double, cắt phần lẻ hoặc làm business arithmetic bằng JS Number. Kiểm tra precision 19/scale 2 bằng decimal khi triển khai.
- Giữ domain payload riêng envelope metadata. Schema không thay thế validation state machine, quan hệ thời gian và transaction/concurrency tests.

## Danh mục đủ 64 event SRS

R1 có link tới payload và bảng publisher/consumer bên dưới. 51 event còn lại chỉ có trạng thái R2, không thiết kế payload hoặc tự gán publisher/consumer R1.

| # | Event | Contract |
| --- | --- | --- |
| 1 | `UserRegistered` | R2 |
| 2 | `UserUpdated` | R2 |
| 3 | `UserDeleted` | R2 |
| 4 | `UserPasswordChanged` | R2 |
| 5 | `UserPasswordReset` | R2 |
| 6 | `UserLoggedIn` | R2 |
| 7 | `UserLoggedOut` | R2 |
| 8 | `UserRoleAssigned` | R2 |
| 9 | `ProductCreated` | R2 |
| 10 | `ProductUpdated` | R2 |
| 11 | `ProductDeleted` | R2 |
| 12 | `ProductStatusChanged` | R2 |
| 13 | `CategoryCreated` | [R1 — payload](#categorycreated) |
| 14 | `CategoryUpdated` | [R1 — payload](#categoryupdated) |
| 15 | `CategoryDeleted` | [R1 — payload](#categorydeleted) |
| 16 | `CartCreated` | R2 |
| 17 | `CartItemAdded` | R2 |
| 18 | `CartItemUpdated` | R2 |
| 19 | `CartItemRemoved` | R2 |
| 20 | `CartExpired` | R2 |
| 21 | `CheckoutStarted` | R2 |
| 22 | `OrderCreated` | [R1 — payload](#ordercreated) |
| 23 | `OrderCancelled` | [R1 — payload](#ordercancelled) |
| 24 | `OrderPaid` | [R1 — payload](#orderpaid) |
| 25 | `OrderPaymentFailed` | R2 |
| 26 | `OrderCompleted` | R2 |
| 27 | `OrderRefundRequested` | R2 |
| 28 | `OrderRefundCompleted` | R2 |
| 29 | `AuctionCreated` | R2 |
| 30 | `AuctionScheduled` | R2 |
| 31 | `AuctionStarted` | [R1 — payload](#auctionstarted) |
| 32 | `BidPlaced` | [R1 — payload](#bidplaced) |
| 33 | `Outbid` | R2 |
| 34 | `AuctionCancelled` | R2 |
| 35 | `AuctionEnded` | [R1 — payload](#auctionended) |
| 36 | `AuctionWon` | [R1 — payload](#auctionwon) |
| 37 | `AuctionFailed` | [R1 — payload](#auctionfailed) |
| 38 | `AuctionPaymentTimeout` | R2 |
| 39 | `AuctionSettled` | [R1 — payload](#auctionsettled) |
| 40 | `InventoryChecked` | R2 |
| 41 | `InventoryReserved` | R2 |
| 42 | `InventoryReleased` | R2 |
| 43 | `InventoryCommitted` | R2 |
| 44 | `InventoryAdjusted` | R2 |
| 45 | `InventoryReservationExpired` | [R1 — payload](#inventoryreservationexpired) |
| 46 | `ShippingQuoteRequested` | R2 |
| 47 | `ShipmentCreated` | R2 |
| 48 | `ShipmentPickedUp` | R2 |
| 49 | `ShipmentInTransit` | R2 |
| 50 | `ShipmentDelivered` | R2 |
| 51 | `ShipmentFailed` | R2 |
| 52 | `ShipmentReturned` | R2 |
| 53 | `NotificationCreated` | R2 |
| 54 | `NotificationSent` | R2 |
| 55 | `NotificationFailed` | R2 |
| 56 | `UserRated` | R2 |
| 57 | `ReputationScoreUpdated` | R2 |
| 58 | `ReputationPenaltyApplied` | R2 |
| 59 | `TrustLevelChanged` | R2 |
| 60 | `SystemConfigUpdated` | R2 |
| 61 | `SystemHealthCheckFailed` | R2 |
| 62 | `SystemHealthCheckRecovered` | R2 |
| 63 | `MessageBrokerUnavailable` | R2 |
| 64 | `MessageBrokerRecovered` | R2 |

## Publisher và consumer của 13 event R1

Publisher dưới đây là yêu cầu publish thực sự trong issue, không suy ra từ CRUD. Subscriber cần có bằng chứng consume; không gọi job đọc DB, HTTP polling hoặc handler chỉ dùng demo là Kafka consumer.

| Event | Topic | Publisher issue / milestone | Consumer / bằng chứng |
| --- | --- | --- | --- |
| `CategoryCreated` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1 | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip, không có recipient; AC yêu cầu DEBUG. |
| `CategoryUpdated` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1 | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): generic no-template filter; không tạo notification. |
| `CategoryDeleted` | `nexus.catalog.events` | [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1 | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): generic no-template filter; không tạo notification. |
| `OrderCreated` | `nexus.commerce.events` | [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118) · S1; [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172) · S3 | notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): thông báo buyer; auction-service [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173): settle AUCTION. |
| `OrderPaid` | `nexus.commerce.events` | [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156) · S3 | fulfillment-service [#160](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/160): confirm reservation bằng orderId. |
| `OrderCancelled` | `nexus.commerce.events` | [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159) · S3 | fulfillment-service [#161](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/161): release HELD; notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107) theo AC [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159): thông báo buyer. |
| `AuctionStarted` | `nexus.auction.events` | [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) · S2 | Sprint chưa chỉ định issue subscriber; đưa vào review consumer bên dưới. |
| `AuctionEnded` | `nexus.auction.events` | [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) · S2 | Sprint chưa chỉ định issue subscriber; #170 đọc trạng thái ENDED từ DB, không subscribe event. |
| `BidPlaced` | `nexus.auction.events` | [#146](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/146) · S2 | Sprint chưa chỉ định issue subscriber; UI #168/#178 đọc HTTP polling. |
| `AuctionWon` | `nexus.auction.events` | [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) · S3 | commerce-service [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172): tạo AUCTION order; winner được thông báo gián tiếp qua OrderCreated. |
| `AuctionFailed` | `nexus.auction.events` | [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) · S3 | Sprint chưa chỉ định issue subscriber; #172 chỉ consume AuctionWon. |
| `AuctionSettled` | `nexus.auction.events` | [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173) · S3 | Sprint chưa chỉ định issue subscriber sau khi settlement hoàn tất. |
| `InventoryReservationExpired` | `nexus.fulfillment.events` | [#175](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/175) · S3 | Sprint chưa chỉ định issue subscriber; #161 consume OrderCancelled, không consume event này. |

### Khoảng trống consumer cần review trên MR

Đã có publisher nhưng **6 event chưa được sprint chỉ định issue subscriber**: AuctionStarted, AuctionEnded, BidPlaced, AuctionFailed, AuctionSettled, InventoryReservationExpired. Việc leader chốt R1 xác nhận publication, không tự gán thêm consumer. Các payload bên dưới vẫn đầy đủ để MR review ngay.

- #170 xử lý winner từ trạng thái ENDED trong DB; #168/#178 phục vụ UI polling. Chúng không consume AuctionEnded/BidPlaced qua Kafka.
- #107 có subscription commerce/catalog, template OrderCreated và filter event không template; #159 yêu cầu thêm notification OrderCancelled. Không mở rộng #107 sang auction/fulfillment chỉ để lấp ô consumer.
- #175 đã trả stock và ghi RELEASE trong publisher transaction; #161 chỉ consume OrderCancelled. Không tạo consumer thứ hai trả stock từ InventoryReservationExpired.
- Reviewer chọn consumer issue cụ thể có hành động cần thiết, hoặc chấp nhận publisher-only cho 6 event này và điều chỉnh yêu cầu consumer của #103/#139. Không tự chuyển chúng sang R2 vì leader đã chốt publication R1, cũng không ghi số issue giả.

## Mapping settlement và luồng tích hợp

| Luồng | Xử lý | Issue |
| --- | --- | --- |
| Standard order | OrderCreated→một notification buyer; OrderPaid→confirm reservation | #118→#107; #156→#160 |
| Expiry | OrderCancelled→release HELD + notification; EXPIRED không release lần hai | #159→#161/#107; #175 |
| Auction winner | AuctionWon→AUCTION order→OrderCreated→auction SETTLED + notification→AuctionSettled | #171→#172→#173/#107 |

| AuctionWon payload | Destination #172 | Quy tắc |
| --- | --- | --- |
| auctionId | orders.reference_id | orderType=AUCTION; không lấy auctionId khác từ request. |
| winnerId, sellerId | orders.user_id, orders.seller_id | Buyer/seller của auction. |
| productId, skuId, title, imageUrl | order_items snapshot | Không Feign để lấy field thiếu; sku_code có thể null. |
| quantity=1, finalPrice | order_items.quantity/unit_price/total_price; orders.subtotal | Single-unit là payload proposal; không khẳng định auction có quantity column. |
| paymentDueAt | orders.payment_due_at | Giữ deadline đã tính bởi #170, không thay bằng standard reservation deadline. |
| currency=VND | Kiểm tra currency | orders hiện không có currency column. |

Commerce tự sinh orderId, order number, memo code và order-line ID. #172 yêu cầu prefix khác STANDARD nhưng chưa đặt tên prefix; shipping/tax/discount defaults cần lane chốt khi implementation, không ngầm yêu cầu thêm field chưa có source. processed_events và lookup reference_id đều cần; index reference_id hiện non-unique nên phải có concurrency guard để không tạo hai order cho cùng auction khi eventId khác nhau (#172/#174).

## Payload R1

Schema/example là payload proposal để review; metadata dùng EventEnvelope #102. Không có payload R2 trong phần này.

### CategoryCreated

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1.
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): skip, không có recipient; AC yêu cầu DEBUG.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `categoryId` | UUID category vừa tạo. |
| `name` | Tên category sau thay đổi, tối đa 150 ký tự. |
| `slug` | Slug category sau thay đổi, tối đa 180 ký tự. |
| `parentId` | UUID category cha; null nếu là root. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryCreatedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "maxLength": 150
    },
    "slug": {
      "type": "string",
      "minLength": 1,
      "maxLength": 180
    },
    "parentId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "categoryId",
    "name",
    "slug",
    "parentId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007",
  "name": "Electronics",
  "slug": "electronics",
  "parentId": null
}
```

Ghi category và outbox trong cùng CategoryService transaction (#106). Root category có parentId=null hợp lệ; đó là nested payload null, không phải envelope null. #107 skip event và log DEBUG; không gửi notification.

### CategoryUpdated

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1.
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): generic no-template filter; không tạo notification.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `categoryId` | UUID category được cập nhật. |
| `name` | Tên category sau thay đổi, tối đa 150 ký tự. |
| `slug` | Slug category sau thay đổi, tối đa 180 ký tự. |
| `parentId` | UUID category cha sau update/move; null nếu là root. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryUpdatedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "maxLength": 150
    },
    "slug": {
      "type": "string",
      "minLength": 1,
      "maxLength": 180
    },
    "parentId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "categoryId",
    "name",
    "slug",
    "parentId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007",
  "name": "Electronics",
  "slug": "electronics",
  "parentId": null
}
```

Snapshot sau update/move; parentId=null cho root. #106 ghi state/outbox chung transaction. #107 áp dụng quy tắc no-template filter. #106 yêu cầu thứ tự create/update/delete cho cùng category; cần kiểm thử nhiều relay, không chỉ dựa ORDER BY occurred_at.

### CategoryDeleted

- Topic: `nexus.catalog.events`.
- Publisher: [#106](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/106) · S1.
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): generic no-template filter; không tạo notification.
- Envelope aggregateId: `categoryId`; Kafka key: `categoryId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `categoryId` | UUID category đã xóa; giữ identity trong event sau khi bản ghi không còn đọc được. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "CategoryDeletedPayload",
  "type": "object",
  "properties": {
    "categoryId": {
      "type": "string",
      "format": "uuid"
    }
  },
  "required": [
    "categoryId"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "categoryId": "00000000-0000-4000-8000-000000000007"
}
```

Chỉ mang categoryId của bản ghi đã xóa. Guard từ chối vì có child/product hoặc transaction rollback thì không publish. Consumer filter không tạo notification và không thực hiện delete lần nữa.

### OrderCreated

- Topic: `nexus.commerce.events`.
- Publisher: [#118](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/118) · S1; [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172) · S3.
- Consumer: notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107): thông báo buyer; auction-service [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173): settle AUCTION.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `orderId` | orders.id; identity của order vừa tạo. |
| `buyerId` | orders.user_id; recipient của notification, lấy từ order đã lưu. |
| `orderNumber` | orders.order_number; mã hiển thị cho buyer. |
| `orderType` | STANDARD hoặc AUCTION theo orders.order_type. |
| `referenceId` | UUID auction nếu orderType=AUCTION; null cho STANDARD. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderCreatedPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null
}
```

**AUCTION order example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "AUC-20261120-0001",
  "orderType": "AUCTION",
  "referenceId": "00000000-0000-4000-8000-000000000003"
}
```

#118 tạo STANDARD order; #172 tạo AUCTION order. STANDARD có referenceId=null; AUCTION có referenceId=auctionId. Order/outbox commit cùng transaction. #107 dùng buyerId/orderNumber để tạo một notification; #173 chỉ xử lý AUCTION để settle. Không tạo notification trực tiếp thứ hai từ AuctionWon. Idempotency retry không tạo thêm event.

### OrderPaid

- Topic: `nexus.commerce.events`.
- Publisher: [#156](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/156) · S3.
- Consumer: fulfillment-service [#160](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/160): confirm reservation bằng orderId.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `orderId` | orders.id; fulfillment tìm reservation qua order_id. |
| `buyerId` | orders.user_id; UUID buyer của order. |
| `orderNumber` | orders.order_number; mã order tại thời điểm thanh toán. |
| `orderType` | STANDARD hoặc AUCTION theo orders.order_type. |
| `referenceId` | UUID auction nếu orderType=AUCTION; null cho STANDARD. |
| `paidAt` | Thời điểm thanh toán thành công đã lưu, UTC. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderPaidPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "paidAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId",
    "paidAt"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "paidAt": "2026-11-20T10:00:00Z"
}
```

#156 xác nhận thanh toán đúng amount và đúng hạn, PENDING→PAID, history/payment/outbox chung transaction. Dedupe bằng Sepay transaction ID. #157 late payment hoặc order CANCELED ghi UNRECONCILED, không phát OrderPaid. Amount khác total cũng giữ order và UNRECONCILED/WARN theo #156; giải quyết under/overpayment ngoài R1. #160 tìm reservation bằng orderId; không yêu cầu reservation IDs trong payload. AUCTION không reservation thì INFO/mark processed, không retry vô hạn.

### OrderCancelled

- Topic: `nexus.commerce.events`.
- Publisher: [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159) · S3.
- Consumer: fulfillment-service [#161](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/161): release HELD; notification-service [#107](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/107) theo AC [#159](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/159): thông báo buyer.
- Envelope aggregateId: `orderId`; Kafka key: `orderId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `orderId` | orders.id; fulfillment tìm reservation qua order_id. |
| `buyerId` | orders.user_id; recipient của notification hủy đơn. |
| `orderNumber` | orders.order_number; mã hiển thị trong notification. |
| `orderType` | STANDARD hoặc AUCTION theo orders.order_type. |
| `referenceId` | UUID auction nếu orderType=AUCTION; null cho STANDARD. |
| `cancelledAt` | Thời điểm PENDING chuyển sang CANCELED. |
| `reason` | Mã PAYMENT_DEADLINE_EXPIRED đề xuất cho expiry job; #159 yêu cầu reason nhưng chưa đặt tên mã. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "OrderCancelledPayload",
  "type": "object",
  "properties": {
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "buyerId": {
      "type": "string",
      "format": "uuid"
    },
    "orderNumber": {
      "type": "string",
      "minLength": 1,
      "maxLength": 40
    },
    "orderType": {
      "type": "string",
      "enum": [
        "STANDARD",
        "AUCTION"
      ]
    },
    "referenceId": {
      "anyOf": [
        {
          "type": "string",
          "format": "uuid"
        },
        {
          "type": "null"
        }
      ]
    },
    "cancelledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "reason": {
      "const": "PAYMENT_DEADLINE_EXPIRED"
    }
  },
  "required": [
    "orderId",
    "buyerId",
    "orderNumber",
    "orderType",
    "referenceId",
    "cancelledAt",
    "reason"
  ],
  "additionalProperties": true,
  "allOf": [
    {
      "if": {
        "properties": {
          "orderType": {
            "const": "AUCTION"
          }
        }
      },
      "then": {
        "properties": {
          "referenceId": {
            "type": "string",
            "format": "uuid"
          }
        }
      },
      "else": {
        "properties": {
          "referenceId": {
            "type": "null"
          }
        }
      }
    }
  ]
}
```

**Payload example**

```json
{
  "orderId": "00000000-0000-4000-8000-000000000001",
  "buyerId": "00000000-0000-4000-8000-000000000002",
  "orderNumber": "NX-20261120-0001",
  "orderType": "STANDARD",
  "referenceId": null,
  "cancelledAt": "2026-11-20T10:00:00Z",
  "reason": "PAYMENT_DEADLINE_EXPIRED"
}
```

Publisher #159 chạy mỗi 30 giây, chuyển PENDING→CANCELED do reservation deadline và ghi reason/history/outbox cùng transaction. reason=PAYMENT_DEADLINE_EXPIRED là tên mã đề xuất cho đường expiry này. PAID không bị job hủy; chạy lại không phát thêm event. #161 release HELD, bỏ qua EXPIRED; notification theo AC #159 mở rộng template của #107. Proposal này không tự khẳng định manual PATCH #116 publish, hoặc #159 đã hỗ trợ deadline riêng AUCTION không có reservation; hai đường đó cần review triển khai.

### AuctionStarted

- Topic: `nexus.auction.events`.
- Publisher: [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) · S2.
- Consumer: Sprint chưa chỉ định issue subscriber; đưa vào review consumer bên dưới.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auctions.id. |
| `sellerId` | UUID seller từ auctions.seller_id. |
| `productId` | UUID product snapshot của auction. |
| `skuId` | UUID SKU snapshot của auction. |
| `startAt` | Thời gian bắt đầu đã lên lịch. |
| `endAt` | Deadline hiệu lực, gồm gia hạn anti-sniping đã commit. |
| `startedAt` | Thời điểm state machine thực sự chuyển sang ACTIVE. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionStartedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "startAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "startedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "startAt",
    "endAt",
    "startedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "startAt": "2026-11-20T08:00:00Z",
  "endAt": "2026-11-20T10:00:00Z",
  "startedAt": "2026-11-20T08:00:01Z"
}
```

#145 ghi SCHEDULED→ACTIVE và event atomically. startAt<endAt, startedAt là transition thực tế; scheduler có thể trễ. Hai scheduler không tạo event trùng. Consumer chưa được issue chỉ định; không thêm notification hay audit handler chỉ để điền bảng.

### AuctionEnded

- Topic: `nexus.auction.events`.
- Publisher: [#145](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/145) · S2.
- Consumer: Sprint chưa chỉ định issue subscriber; #170 đọc trạng thái ENDED từ DB, không subscribe event.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auctions.id. |
| `sellerId` | UUID seller từ auctions.seller_id. |
| `productId` | UUID product snapshot của auction. |
| `skuId` | UUID SKU snapshot của auction. |
| `startAt` | Thời gian bắt đầu đã lên lịch. |
| `endAt` | Deadline hiệu lực sau mọi gia hạn anti-sniping. |
| `endedAt` | Thời điểm state machine thực sự chuyển sang ENDED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionEndedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "startAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "endedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "startAt",
    "endAt",
    "endedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "startAt": "2026-11-20T08:00:00Z",
  "endAt": "2026-11-20T10:00:00Z",
  "endedAt": "2026-11-20T10:00:00Z"
}
```

#145 ghi ACTIVE→ENDED; endAt là deadline sau anti-sniping, endedAt≥endAt. #170 tìm ENDED trong database để chọn winner, không phải Kafka subscriber. Không dùng polling #168/#178 làm bằng chứng có consumer.

### BidPlaced

- Topic: `nexus.auction.events`.
- Publisher: [#146](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/146) · S2.
- Consumer: Sprint chưa chỉ định issue subscriber; UI #168/#178 đọc HTTP polling.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auction nhận bid được chấp nhận. |
| `bidId` | UUID bid đã lưu. |
| `bidderId` | UUID bidder được chấp nhận. |
| `amount` | Giá trị VND dương, tối đa hai chữ số thập phân; Java parse/serialize bằng BigDecimal. |
| `currency` | VND. |
| `placedAt` | Thời điểm bid được ghi nhận, UTC. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "BidPlacedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "bidId": {
      "type": "string",
      "format": "uuid"
    },
    "bidderId": {
      "type": "string",
      "format": "uuid"
    },
    "amount": {
      "type": "number",
      "exclusiveMinimum": 0,
      "multipleOf": 0.01
    },
    "currency": {
      "const": "VND"
    },
    "placedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "bidId",
    "bidderId",
    "amount",
    "currency",
    "placedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "bidId": "00000000-0000-4000-8000-000000000008",
  "bidderId": "00000000-0000-4000-8000-000000000002",
  "amount": 1500000,
  "currency": "VND",
  "placedAt": "2026-11-20T10:00:00Z"
}
```

#146 chỉ publish sau conditional UPDATE hợp lệ và INSERT bid cùng transaction; refused/rollback không có event. amount>0 và thỏa currentPrice+bidIncrement. #147 retry cùng Idempotency-Key không phát event thứ hai; #149 có thể gia hạn endAt trong transaction đó. Không phát thêm Outbid trong R1. Tiền theo numeric(19,2)/BigDecimal của #66/#67; mâu thuẫn prose bigint trong #140/#146 cần đồng bộ khi triển khai.

### AuctionWon

- Topic: `nexus.auction.events`.
- Publisher: [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) · S3.
- Consumer: commerce-service [#172](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/172): tạo AUCTION order; winner được thông báo gián tiếp qua OrderCreated.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auction đã xác định người thắng. |
| `sellerId` | UUID seller snapshot từ auction. |
| `productId` | UUID product snapshot từ auction. |
| `skuId` | UUID SKU snapshot từ auction. |
| `winnerId` | UUID bidder thắng; trở thành orders.user_id. |
| `finalPrice` | Giá thắng VND, tối đa hai chữ số thập phân; BigDecimal theo #66/#67. |
| `currency` | VND. |
| `title` | Tên product snapshot khi tạo auction; trở thành order_items.product_name. |
| `imageUrl` | URI ảnh snapshot; bắt buộc non-null theo #139/#171. DB nullable cần chốt nguồn ảnh trước publication. |
| `quantity` | Đề xuất cố định 1 đơn vị mỗi auction; schema auction không có quantity column. |
| `endAt` | Thời điểm kết thúc hiệu lực sau gia hạn. |
| `paymentDueAt` | endAt cộng auction.payment-deadline-hours theo #170; commerce không tính lại. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionWonPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "winnerId": {
      "type": "string",
      "format": "uuid"
    },
    "finalPrice": {
      "type": "number",
      "minimum": 0,
      "multipleOf": 0.01
    },
    "currency": {
      "const": "VND"
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 255
    },
    "imageUrl": {
      "type": "string",
      "minLength": 1,
      "format": "uri",
      "maxLength": 500
    },
    "quantity": {
      "const": 1
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "paymentDueAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "winnerId",
    "finalPrice",
    "currency",
    "title",
    "imageUrl",
    "quantity",
    "endAt",
    "paymentDueAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "winnerId": "00000000-0000-4000-8000-000000000002",
  "finalPrice": 1500000,
  "currency": "VND",
  "title": "Auction item",
  "imageUrl": "https://example.com/product.jpg",
  "quantity": 1,
  "endAt": "2026-11-20T10:00:00Z",
  "paymentDueAt": "2026-11-21T10:00:00Z"
}
```

#170 chọn bid cao nhất, bid sớm hơn thắng khi cùng amount; paymentDueAt=endAt+config auction.payment-deadline-hours. #171 lấy payload từ auction snapshot và ghi WON/outbox atomically; chạy lại không publish. Không Feign để lấy dữ liệu thiếu ở settlement. quantity=1 là đề xuất vì auction chỉ có một SKU và không có quantity column. imageUrl được yêu cầu non-null theo #139/#171, trong khi DB cho phép null: phải chốt nguồn ảnh/validation trước khi publisher triển khai. #172 tạo order từ event, không reserve stock; #173 settle sau OrderCreated, không sau payment.

### AuctionFailed

- Topic: `nexus.auction.events`.
- Publisher: [#171](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/171) · S3.
- Consumer: Sprint chưa chỉ định issue subscriber; #172 chỉ consume AuctionWon.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auction thất bại vì không có bid. |
| `sellerId` | UUID seller snapshot từ auction. |
| `productId` | UUID product snapshot từ auction. |
| `skuId` | UUID SKU snapshot từ auction. |
| `reason` | Mã NO_BIDS đề xuất cho nhánh không có bid của #170. |
| `endAt` | Deadline kết thúc hiệu lực của auction. |
| `failedAt` | Thời điểm state machine chuyển sang FAILED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionFailedPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "sellerId": {
      "type": "string",
      "format": "uuid"
    },
    "productId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "reason": {
      "const": "NO_BIDS"
    },
    "endAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "failedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "sellerId",
    "productId",
    "skuId",
    "reason",
    "endAt",
    "failedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "sellerId": "00000000-0000-4000-8000-000000000004",
  "productId": "00000000-0000-4000-8000-000000000006",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "reason": "NO_BIDS",
  "endAt": "2026-11-20T10:00:00Z",
  "failedAt": "2026-11-20T10:00:00Z"
}
```

Không có bid theo #170 nên #171 ghi FAILED và AuctionFailed trong cùng transaction, reason=NO_BIDS. Không đưa winnerId/finalPrice giả vào payload; mọi field được publish giữ non-null theo #171. Rerun/rollback không tạo event. #172 không xử lý event này; chưa có consumer issue tiếp theo.

### AuctionSettled

- Topic: `nexus.auction.events`.
- Publisher: [#173](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/173) · S3.
- Consumer: Sprint chưa chỉ định issue subscriber sau khi settlement hoàn tất.
- Envelope aggregateId: `auctionId`; Kafka key: `auctionId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `auctionId` | UUID auction từ OrderCreated.referenceId. |
| `orderId` | UUID AUCTION order vừa tạo. |
| `settledAt` | Thời điểm chuyển WON sang SETTLED, UTC. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AuctionSettledPayload",
  "type": "object",
  "properties": {
    "auctionId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "settledAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "auctionId",
    "orderId",
    "settledAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "auctionId": "00000000-0000-4000-8000-000000000003",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "settledAt": "2026-11-20T10:00:00Z"
}
```

#173 consume AUCTION OrderCreated, chuyển WON→SETTLED, lưu orderId và phát một AuctionSettled chung transaction với processed marker. STANDARD bỏ qua; auction không WON thì WARN, không đổi state. SETTLED nghĩa là order đã tồn tại, không có nghĩa đã trả tiền. Không gọi inventory commit từ event này.

### InventoryReservationExpired

- Topic: `nexus.fulfillment.events`.
- Publisher: [#175](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/175) · S3.
- Consumer: Sprint chưa chỉ định issue subscriber; #161 consume OrderCancelled, không consume event này.
- Envelope aggregateId: `reservationId`; Kafka key: `orderId`.

| Payload field | Ý nghĩa |
| --- | --- |
| `reservationId` | UUID reservation đã hết hạn; envelope aggregateId. |
| `orderId` | UUID order sở hữu reservation; Kafka key đề xuất. |
| `skuId` | UUID SKU đã được giữ. |
| `warehouseId` | UUID warehouse, không phải warehouse code WH_001. |
| `quantity` | Số lượng được trả về available trong expiry transaction. |
| `expiresAt` | Deadline gốc của reservation; config refresh không đổi deadline đã lưu. |
| `releasedAt` | Thời điểm expiry transaction trả stock và ghi EXPIRED. |

**Payload schema**

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "InventoryReservationExpiredPayload",
  "type": "object",
  "properties": {
    "reservationId": {
      "type": "string",
      "format": "uuid"
    },
    "orderId": {
      "type": "string",
      "format": "uuid"
    },
    "skuId": {
      "type": "string",
      "format": "uuid"
    },
    "warehouseId": {
      "type": "string",
      "format": "uuid"
    },
    "quantity": {
      "type": "integer",
      "minimum": 1
    },
    "expiresAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    },
    "releasedAt": {
      "type": "string",
      "format": "date-time",
      "pattern": "Z$"
    }
  },
  "required": [
    "reservationId",
    "orderId",
    "skuId",
    "warehouseId",
    "quantity",
    "expiresAt",
    "releasedAt"
  ],
  "additionalProperties": true
}
```

**Payload example**

```json
{
  "reservationId": "00000000-0000-4000-8000-000000000009",
  "orderId": "00000000-0000-4000-8000-000000000001",
  "skuId": "00000000-0000-4000-8000-000000000005",
  "warehouseId": "00000000-0000-4000-8000-000000000010",
  "quantity": 2,
  "expiresAt": "2026-11-20T10:00:00Z",
  "releasedAt": "2026-11-20T10:00:01Z"
}
```

#175 batch-expire HELD quá hạn, trả quantity về available, ghi RELEASE movement và outbox trong cùng transaction; releasedAt≥expiresAt. Một event mỗi reservation thực sự đổi; rerun không thay stock hoặc thêm event. #134 quy định reservation R1 có orderId, cart_id unused; warehouseId là UUID đã resolve từ warehouse code. Không phát InventoryReleased trong R1. #161 consume OrderCancelled và bỏ qua EXPIRED; consumer của InventoryReservationExpired không được trả stock lần nữa vì publisher đã làm việc đó.

## Bàn giao và kiểm chứng

| Issue | Trách nhiệm | Dependency |
| --- | --- | --- |
| #104 | Outbox writer/relay chung; identity, concurrency claim và ordering. | Chờ #102 theo issue; chạy song song #105. |
| #105 | IdempotentConsumer theo eventId + consumer, atomic effects/marker, concurrent duplicate và retry khi rollback. | Chờ #102; không cần consumer business mới để xây wrapper. |
| #106 | Category 3 event theo schema, rollback không event, create/update/delete đúng thứ tự. | #104. |
| #107 | OrderCreated notification; CategoryCreated/no-template skip; OrderCancelled theo AC #159. | Issue ghi chờ #103 + #105; dùng hand-published envelope trước publisher thật. |
| #118 | OrderCreated outbox và notification end-to-end. | #113/#104/#107. |
| #139 | Payload settlement/payment/expiry và OpenAPI/webhook/gateway tương ứng. | Hai phía AuctionWon review; không tự hoàn tất chỉ vì #103 có schema. |

| Kiểm thử implementation cần đạt | Issue |
| --- | --- |
| Kafka dừng: business/outbox vẫn commit; relay gửi sau, giữ eventId; business rollback không để event. | #104/#106/#118 |
| Duplicate/concurrent duplicate chỉ chạy effect một lần mỗi consumer; throw thì rollback marker để retry. | #105 |
| Hai relay vẫn giữ create/update/delete category cùng aggregate đúng thứ tự. | #104/#106 |
| OrderCreated→một notification; chỉ AUCTION settle. | #107/#118/#172/#173 |
| OrderPaid AUCTION không reservation: INFO, hoàn tất processed; order nhiều dòng confirm tất cả hoặc không dòng nào. | #160 |
| OrderCancelled/expiry race không double-release hoặc movement trùng. | #159/#161/#175 |
| Replay/crash AuctionWon cuối cùng một order và một OrderCreated, auction SETTLED. | #172/#174 |
| Scheduler không có HTTP request vẫn có trace trước factory; relay không tạo identity mới khi retry. | #102/#104/#145/#159/#175 |
| BigDecimal giữ 150000.50 và 0.29, không cắt phần lẻ. | #66/#67, publisher/consumer money |

### Validation tài liệu

Đã kiểm tra các JSON block thực trong file bằng Ajv Draft 7/format checks:

- 64 tên SRS đúng thứ tự; tập R1 khớp chính xác 13 event leader cung cấp; 51 contract còn lại đúng giá trị R2.
- 13 JSON Schema hợp lệ, 14 payload example hợp lệ (OrderCreated có cả STANDARD và AUCTION).
- 209 negative cases bị từ chối: missing/null, UUID/time sai, sai orderType/referenceId, số tiền âm/hơn hai số lẻ và BidPlaced amount=0.
- 4 decimal checks với 150000.50/0.29 đạt; additive fields được chấp nhận; mọi local anchor/link resolve.

Đây là kiểm tra tài liệu, không phải integration tests DB/Kafka của #104–#175. Sáu consumer gap được kiểm tra là ghi rõ trong tài liệu, không được validator đánh dấu thành consumer đã có.

### Acceptance #103

- [x] 64 tên SRS đúng thứ tự, không trùng hoặc sai spelling InventoryReservationExpired.
- [x] Đúng 13 R1 theo scope leader; 51 dòng còn lại chỉ ghi R2.
- [x] Cả 13 R1 có topic, publisher issue, aggregate/key, JSON Schema và example.
- [x] OrderCancelled có publisher #159, consumer #161 và notification theo #159/#107.
- [x] Envelope/type/topic đối chiếu #102 ccbdd28; không mở rộng publication/subscription ngoài scope đã chốt.
- [ ] Hai lane chốt 6 subscriber issue còn thiếu hoặc chấp nhận publisher-only và điều chỉnh tiêu chí consumer #103/#139.
- [ ] Hai backend lane approve payload MR theo #103; approval thực tế ghi trong GitLab.

Scope R1/R2 đã được chốt; phần review còn lại tập trung payload, mapping settlement, source tiền/image và consumer ownership. Đây là bản hoàn chỉnh để tạo MR review, không phải tuyên bố mọi acceptance đã được approve hoặc implementation đã hoàn tất.
