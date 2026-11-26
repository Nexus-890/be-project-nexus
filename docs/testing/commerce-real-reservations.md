# #138 — Commerce dùng reservation và shipping quote thật

## Contract và phạm vi

Nguồn: issue #138, reservation #134/#137, pricing #188, shipping #201 đã merge,
SRS §3.4.2–3.4.3/§3.6.1–3.6.2 và OpenAPI hiện tại. #201 chuyển địa chỉ sang
GHN v3 hai cấp, nên checkout dùng `provinceCode` và `wardCode`, không yêu cầu
`districtCode`. Không trộn `_id` v3 với mã địa giới cũ.

`POST /api/v1/orders` nhận `Idempotency-Key` UUID và body:

```json
{
  "paymentMethod": "SEPAY",
  "shippingAddress": {
    "recipientName": "Người nhận thực tế",
    "phoneNumber": "0901234567",
    "addressLine": "Địa chỉ giao hàng thực tế",
    "wardCode": "1003646",
    "provinceCode": "1000001",
    "countryCode": "VN"
  }
}
```

Các mã trên chỉ minh họa cấu trúc. Lấy cặp tỉnh/phường đang hoạt động từ API
địa giới của môi trường đang kiểm tra. `addressLine` tối đa 255 ký tự; phone khớp
`^(0[0-9]{9}|\+84[0-9]{9})$`. Thiếu `wardCode` hoặc `provinceCode` trả 400.
Không gửi giá, phí ship hay số đo kiện từ phía buyer để quyết định số tiền đơn.

`POST /api/v1/checkout/preview` dùng cùng địa chỉ và cách báo giá theo seller.
Preview không ghi cart/order/reservation/event. SKU/product inactive làm shipping
chưa xác định; price-change issue vẫn có thể báo giá theo dữ liệu catalog hiện tại.
`GET /api/v1/carts/me/pricing`
không có địa chỉ giao hàng nên giữ `estimatedShippingFee=null`.

## Nguồn số đo kiện

Seller nhập `shippingParcel` của từng SKU qua API catalog hiện có:

```json
{
  "weightGrams": 1200,
  "lengthCm": 30,
  "widthCm": 20,
  "heightCm": 15
}
```

Đây là ví dụ, không phải giá trị mặc định hoặc dữ liệu dùng để backfill SKU cũ.
Seller phải cung cấp số đo thật. Catalog là chủ sở hữu dữ liệu; Commerce đọc qua
API, không đọc DB catalog. SKU cũ chưa có metadata vẫn đọc được, nhưng checkout
SKU đó trả 409 `COMMERCE_SHIPPING_METADATA_REQUIRED` kèm `skuId`.

Mỗi seller order được đóng một kiện theo quy tắc xác định:

- Cân nặng = tổng `unit.weightGrams × quantity`.
- Chiều dài/rộng = giá trị lớn nhất của các SKU trong seller order.
- Chiều cao = tổng `unit.heightCm × quantity`.

Giới hạn quote #201: 1–100 line, mỗi quantity 1–999, kiện 1–50.000 gram và mỗi
kích thước 1–200 cm. Không cắt số lượng, làm tròn hay thay số đo thiếu bằng giá trị
giả. Kiện vượt giới hạn trả 409 `COMMERCE_INVALID_PARCEL`. R1 vẫn một shipment
mỗi seller order; chia thành nhiều kiện không thuộc thay đổi này.

## Thứ tự checkout và tính tiền

1. Khóa cart của buyer, revalidate giá/catalog và dựng seller orders.
2. Báo giá mọi seller order qua `POST /api/v1/shipping/quotes`, thông qua pricing
   dùng chung. Quote chỉ preview, không booking shipment. Chọn quote hợp lệ theo
   phí thấp nhất, rồi carrier và ETA để phá hòa. `insuranceValue` không tự lấy
   từ subtotal; việc bỏ trống dùng mặc định 0 của shipping #201.
3. Lưu group/children trong transaction Commerce để có `orderId` thật. Gọi đúng
   một `POST /api/v1/inventory/reservations` cho group với tất cả
   `{orderId, skuId, quantity}`. Inventory giữ mọi line trong một transaction.
4. Gán `order_groups.due_at` và mọi `orders.payment_due_at` bằng reservation
   `expiresAt`; lưu `warehouseId` từ reservation. Ghi history/outbox và checkout
   cart trong cùng transaction Commerce.

Mỗi order tính `subtotal - discountAmount + taxAmount + shippingFee`; group total
là tổng các order. Phí do backend gọi quote, không lấy từ request hoặc frontend
preview cũ. Discount/tax vẫn theo phạm vi R1 hiện tại.

Snapshot `orders.shipping_info` giữ recipient/address hai cấp, `warehouseId`,
parcel và carrier/serviceType được backend chọn. Payment/event consumer dùng
snapshot đã lưu; không tái đọc địa chỉ hoặc số đo hiện tại để thay dữ liệu đơn.

Shipping lỗi trước reserve nên không có reservation. Inventory từ chối hoặc
timeout làm transaction Commerce rollback. Nếu Inventory đã giữ hàng nhưng
response bị mất hoặc Commerce thất bại sau đó, stock có thể ở trạng thái HELD
cho tới expiry job; không gọi release bù trong luồng #138.

## Cấu hình và truy cập

Config Server `commerce-service.yml`:

| Key | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `nexus.order.inventory-base-url` | rỗng | Discovery `inventory-service`; URL override phục vụ môi trường/test |
| `nexus.order.shipping-base-url` | rỗng | Discovery `shipment-service`; URL override phục vụ môi trường/test |
| `nexus.order.remote-timeout-ms` | 1800 | Timeout một lời gọi inventory, giới hạn cấu hình tối đa 2200 ms; mọi quote/reserve dùng chung ngân sách checkout 2600 ms |
| `nexus.order.shipping-timeout-ms` | 3500 | Timeout một lời gọi quote sang shipment-service (tối đa 4000 ms), dài hơn ngân sách nội bộ 3000 ms của shipment; trong checkout vẫn bị cắt bởi ngân sách chung 2600 ms |
| `nexus.order.remote-breaker-failure-threshold` | 5 | Số failure khả dụng trước khi mở circuit riêng theo dependency |
| `nexus.order.remote-breaker-open-ms` | 30000 | Thời gian mở circuit trước probe |

Feign chuyển nguyên bearer của buyer; không retry POST giữ hàng. Lỗi nghiệp vụ
như thiếu stock/địa chỉ sai không được dùng để mở circuit. Commerce không giữ mã
kho. Vì quote diễn ra trước reserve và gửi `warehouseId=null`, cấu hình
`SHIPPING_DEFAULT_WAREHOUSE_ID` phải trỏ đúng kho mặc định mà Inventory resolve.
Xác minh UUID này khi dựng môi trường; không suy ra từ tên kho.

`GET /api/v1/orders/groups/{id}` yêu cầu `ORDER.VIEW`, buyer owner hoặc ADMIN.
Buyer khác nhận 403; group không có nhận 404. `OrderResponse` của detail và list
có `orderGroupId`, `subtotal`, `shippingFee`, `discountAmount`, `taxAmount`.
Gateway kiểm tra privilege rồi chuyển bearer; Commerce kiểm tra ownership.

| Trường hợp | HTTP/code |
| --- | --- |
| Inventory thiếu hàng | 409 `COMMERCE_INSUFFICIENT_STOCK`, có `skuId`, `available`, `requested` |
| Inventory timeout/down/circuit mở | 503 `COMMERCE_INVENTORY_UNAVAILABLE` |
| Shipping timeout/down/circuit mở hoặc response không hợp lệ | 503 `COMMERCE_SHIPPING_UNAVAILABLE` |
| SKU thiếu số đo | 409 `COMMERCE_SHIPPING_METADATA_REQUIRED` |
| Kiện vượt giới hạn | 409 `COMMERCE_INVALID_PARCEL` |
| Province/ward không hợp lệ theo shipping | 400 `COMMERCE_INVALID_SHIPPING_ADDRESS` |
| Carrier không có dịch vụ phù hợp | 422 `COMMERCE_SHIPPING_NOT_AVAILABLE` |

## Cách kiểm tra

Chạy với Java 21, từ từng module tương ứng:

```powershell
# commerce-service: test unit/HTTP và PostgreSQL rollback/concurrency khi Docker sẵn sàng
.\mvnw.cmd '-Duser.timezone=UTC' verify
# catalog-service: metadata/validation/ownership và migration
.\mvnw.cmd '-Duser.timezone=UTC' verify
# api-gateway
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=GatewayPermissionsTest,SecurityConfigTest' test
# từ be-project-nexus
node docs/api/check-contract.mjs
npx.cmd --yes @redocly/cli@2.63.0 lint docs/api/openapi/commerce-service.yaml docs/api/openapi/catalog-service.yaml
git diff --check
```

Đây là các lệnh tái hiện; một lệnh chưa chạy không phải evidence PASS. Full
cross-service AC cần Inventory/PostgreSQL thật, không chỉ mock `InventoryClient`:

| AC cần quan sát | Evidence cần lưu |
| --- | --- |
| Đặt 2 khi available=1 | 409 đúng SKU/count; order/group count và balance không đổi |
| Cart hai seller, seller thứ hai thiếu hàng | Không order/reservation/movement mới ở cả hai seller |
| Checkout thành công | Mỗi order line có reservation HELD với đúng seller `order_id`; warehouse/deadline khớp snapshot |
| Hai buyer mua unit cuối đồng thời | Đúng một 201, một 409; available/reserved và RESERVE ledger đúng |
| Shipping dừng | 503; không order/reservation mới |
| Inventory dừng | 503 trong 3 giây; không order mới; kiểm tra cả group/history/outbox rollback |
| Hai seller | Một quote/phí mỗi seller order; group total đúng tổng breakdown |
| Group/detail/list | Owner đọc đủ children, buyer khác 403, ADMIN đúng privilege đọc được, group ID có trên mọi order |

Không dùng `SELECT count(*)` của DB khác trong production code. Khi test, truy
vấn từng DB bằng fixture/evidence riêng; không tạo FK chéo service hoặc sửa dữ
liệu hiện hữu để ép test xanh.

## Evidence tại checkout hiện tại

Ngày 11/11/2026:

- Gateway focused tests: 44 cases, 0 failure/error/skip, log local
  `D:\GitLab\log\commerce138-gateway-test.log`.
- Contract inventory checker: 81 operations khớp source/spec.
- Redocly 2.63.0 commerce spec: hợp lệ, 0 error; 4 cảnh báo
  `no-ambiguous-paths` do path group được issue quy định giao với các template
  `/orders/{id}/{invoice,status,cancel,history}`. Rule không bị tắt; Spring chọn
  literal path và UUID conversion kiểm tra ID. Gateway tests xác minh đúng
  privilege/bearer của group path.
- Catalog full verify: 124 tests, 0 failure/error/skip; gồm migration V8 trên
  PostgreSQL 18, metadata nullable cho SKU cũ, bounds/all-or-none CHECK và quyền seller.
- Commerce full verify trên integration: 392 tests, 0 failure/error, 5 simulation
  Catalog có sẵn bỏ qua do chưa cấu hình external URL. Sau thay đổi fixture isolation
  và thêm hai HTTP contract cases, focused rerun: 38 tests, 0 failure/error/skip.
- `commerce-reservation-smoke.py`: 12 checks PASS qua Commerce, Inventory và
  Shipment/FAKE thực, mỗi service có database riêng trong container PostgreSQL tạm.
  Catalog/JWKS là HTTP fixtures; không phải GHN sandbox, Keycloak hoặc remote CI.
  Quan sát một 201/một 409 khi mua unit cuối đồng thời; hai-shop thiếu hàng không
  tạo order/reservation; deadline/warehouse/fees, preview, replay, owner và missing-ward
  đúng. Shipping dừng trả 503 trong 47 ms; Inventory dừng trả 503 trong 484 ms.
  Evidence local: `D:\GitLab\log\commerce138-real-smoke\result.json`.

Chạy smoke sau khi package ba service, dùng Python có thư viện `cryptography` và
JDK 21: `python docs/testing/commerce-reservation-smoke.py --java <java21> --output <logs>`.
Script tạo container/JVM riêng và chỉ dọn tài nguyên của lần chạy đó; không dùng DB local hiện hữu.
Sau khi tích hợp develop có #156, `OrderPaid.Address.districtCode` là optional,
bỏ khỏi JSON khi null và giữ nguyên khi đọc snapshot ba cấp lịch sử; schema event
đã bỏ district khỏi required. Renderer receipt #204 cũng cần
đọc `recipient.address` ngoài snapshot `shippingAddress` lịch sử.

### Source MR cuối sau cập nhật develop da482b76

Đã giữ PriceBreakdown/CHECK của #255 và quản lý kho #199; các thay đổi sẵn có
trên develop không nằm trong diff #138. Trên đúng source MR:

- Commerce full verify: 322 cases, 0 failure/error, 5 external Catalog simulation skips có sẵn.
- Catalog full verify: 131 cases, 0 failure/error/skip.
- Gateway focused: 43 cases, 0 failure/error/skip.
- Contract checker: 83 operations; Redocly hai spec hợp lệ, còn 3 cảnh báo path ambiguity
  trên source chưa có invoice #204 (integration có 4 cảnh báo).
- Cross-service smoke: 12 checks PASS với Commerce/Inventory mới và Shipment/FAKE.
  Inventory dừng: 503 trong 406 ms; Shipment dừng: 503 trong 63 ms.
  Evidence: `D:\GitLab\log\commerce138-feature-latest-smoke\result.json`.
- Primary integration sau cập nhật cùng develop: 51 focused tests #138/#156 PASS;
  các thay đổi Payment đang làm riêng được giữ nguyên, không commit trong #138.

Lỗi kết nối từng gặp lúc dựng smoke được xác định ở HTTP fixture sử dụng HTTP/1.0;
fixture đã dùng HTTP/1.1 và chờ PostgreSQL TCP sẵn sàng. Không thêm retry production
hoặc đổi assertion để làm kết quả xanh. Không có bằng chứng GHN thật hoặc CI xanh
trong các kết quả local này.

### Regression địa chỉ checkout và payment confirmation

Review !156/note264201: đã merge develop `02385a14` chứa confirmation #156.
Test mới gọi OrderService checkout hai seller bằng địa chỉ tỉnh/phường thật từ
request, rồi gọi PaymentEventConsumer với PostgreSQL/Flyway/outbox thật; chỉ mock
Catalog/Inventory/Shipping và tracer. Trước fix, test tái hiện đúng lỗi
`Order has an incomplete shipping snapshot`. Sau fix, group và hai order PAID,
mỗi order có một history PAID và đúng một OrderPaid hợp schema, kể cả replay cùng
eventId hoặc envelope mới cho cùng receipt; JSON không chứa district giả/null.
Test snapshot ba cấp cũ vẫn giữ districtCode đã lưu.

Ngày 11/11/2026: focused 46 tests PASS, 0 failure/error/skip (44 consumer unit và
2 PostgreSQL regressions). Lệnh từ commerce-service:

```powershell
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=CheckoutPaymentConfirmedTest#checkoutTwoLevelAddressPaysBothSellerOrdersAndPublishesOneOrderPaidPerOrderOnReplay+completeManifestPaysBothOrdersAndPublishesSchemaValidStoredShippingSnapshots,CheckoutPaymentConfirmedConsumerTest' test
```

Contract checker 83 operations PASS; diff whitespace PASS. Chỉ chạy focused tests
cho bản sửa này theo yêu cầu, không coi là full verify hoặc remote CI mới.
