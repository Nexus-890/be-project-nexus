# Kịch bản demo #158 — bản chuẩn bị

Ngày đối chiếu: 17/11/2026. **Chưa chạy trên STAGING.** Người dùng xác nhận chưa có
URL STAGING và chưa phân công người thuyết trình/người thao tác. Các mốc dưới đây
là ngân sách dự kiến; chỉ ghi GREEN sau khi chạy thật đủ tám bước.

## Điều kiện trước khi bấm giờ

- Điền URL, commit triển khai, hai người phụ trách và biên bản tại
  [evidence chuyển khoản](test-evidence/sepay-staging-transfer.md).
- Deploy đủ chuỗi Catalog → Commerce → Payment → Kafka → Commerce và Auction →
  Inventory → Commerce. Kiểm tra gateway, Keycloak và giao diện dùng cùng môi trường.
- Chuẩn bị hai browser/profile BUYER khác nhau, một SELLER sở hữu sản phẩm demo,
  Admin đã đăng nhập cho việc chẩn đoán; token được giữ ngoài Git.
- Kiểm tra từng SKU dùng trong demo: ACTIVE, có ảnh tải được, stock khả dụng đủ cho
  giỏ hai món và auction, metadata kiện hàng hợp lệ. Chuẩn bị địa chỉ giao hàng hợp lệ.
- Seed hiện tại **chưa hoàn thành #176**: SKU UUID sinh ngẫu nhiên ở Catalog V4,
  V8 để parcel NULL, Inventory chưa seed stock, ảnh cần được provision. Cần hoàn
  thiện fixture nhất quán bằng API/seed mới và kiểm tra fresh/restart trước buổi chạy.
  Không lấy UUID tình cờ trong DB local để hardcode vào migration môi trường khác.
- [ADR-007](adr/ADR-007-payment-provider.md) ghi payer Nguyễn Trường An, giới hạn
  smoke test đã thống nhất 10.000 VND/lần, nhận tại VietinBank của Trần Quốc Chung.
  Tổng session, gồm phí giao hàng và cả hai món, phải đúng số tiền được phép. Seed
  giá hiện hữu chưa bảo đảm tổng 10.000 VND: chuẩn bị fixture qua nghiệp vụ hợp lệ
  hoặc xin quyết định mới của người phụ trách trước khi chuyển tiền; không sửa DB
  tổng tiền và không tự tăng số tiền chuyển theo QR hiện có.
- Webhook SePay Live trỏ tới `https://<host>/api/v1/webhooks/sepay`; xác nhận HMAC
  secret runtime với người giữ credential, không đưa secret vào log/ảnh/MR.

### Sai khác cấu hình chỉ dành cho STAGING

Config Client của Auction phải yêu cầu profile `staging` từ Config Server. Kiểm tra
`GET <config-server>/auction-service/staging` và cấu hình đã bind vào instance.
Không chỉ bật `staging` cho riêng tiến trình Config Server.

| Thuộc tính `nexus.auction.*` | Mặc định | STAGING demo |
|---|---:|---:|
| `min-duration-minutes` | 60 | 1 |
| `anti-sniping-extension-minutes` | 5 | 1 |
| `anti-sniping-max-extension-minutes` | 30 | 1 |

Giới hạn một phút vẫn được validate. Auction demo có thời lượng ban đầu ba phút;
bid muộn chỉ có thể cộng tổng một phút để còn ngân sách dưới mười phút. Đây là
sai khác của môi trường demo; không đổi luật mặc định của môi trường khác. Không
refresh config, sửa dữ liệu hoặc restart container trong một lần chạy GREEN.

## Tám bước

Tính thời gian từ thao tác đầu tiên đến khi QR auction xuất hiện. Mục tiêu **≤9 phút**
để còn khoảng đệm; tiêu chí issue là **dưới 10 phút**. Latency chuyển khoản thực tế
phải được đo trước và thay vào ngân sách, không mặc định coi là vài giây.

| Bước / ngân sách tích lũy | Người thao tác và màn hình mong đợi | Khi khác mong đợi |
|---|---|---|
| 1 / 00:00–00:45 | Đăng ký BUYER mới; đăng nhập qua Keycloak; giao diện nhận đúng tài khoản. | Ghi lỗi đăng ký/redirect, dừng lần chạy; không dùng tài khoản cũ rồi ghi bước này PASS. |
| 2 / 00:45–01:30 | Vào category, mở sản phẩm có ảnh, thêm hai món vào giỏ; số lượng và giá đúng fixture. | Dừng nếu ảnh, SKU hoặc stock lỗi; sửa fixture trước lần chạy kế tiếp. |
| 3 / 01:30–02:00 | Checkout với địa chỉ hợp lệ; thấy nhóm đơn, QR, memo, số tiền và deadline. Ghi `orderGroupId`, mọi `orderId`, session và memo. | Không có QR: giữ response/lỗi; kiểm tra snapshot kiện hàng, quote và manifest Payment. Không tạo lại đơn liên tục. |
| 4 / 02:00–03:00 | Payer kiểm tra số tiền/memo/đích nhận rồi chuyển từ ứng dụng ngân hàng thật. Giữ nguyên tab thanh toán; trạng thái tự chuyển PAID. Ghi thời điểm chuyển và lần đầu UI hiển thị PAID. | Hết latency đã đo + khoảng đệm: dùng [fallback](demo-fallback.md); lần này không GREEN. Không refresh để chứng minh AC tự cập nhật. |
| 5 / 03:00–03:45 | SELLER mở màn hình quản lý auction, chọn SKU của mình và thời lượng `3 minutes · demo`. Đặt Start ở phút gần nhất trong tương lai rồi chọn **Schedule Auction**; thấy SCHEDULED rồi ACTIVE trong một phút. Save Draft chỉ tạo CREATED. | Lỗi duration: kiểm tra profile trước lần chạy khác. Kiểm tra lại Start vì preset demo có thể đặt xa hơn một phút. Thiếu stock/ảnh/ownership: dừng; không đổi config trong lần chạy. |
| 6 / 03:45–07:45 | Hai browser BUYER bid luân phiên, giá tăng và history xuất hiện. Gửi một bid hợp lệ trong cửa sổ anti-sniping; cả hai màn hình thấy deadline tăng tối đa một phút. | Ghi request/event lỗi, kiểm tra state qua API để chẩn đoán; không sửa giá/endAt trong DB. Bid bị từ chối vì thấp không tính là bid chấp nhận. |
| 7 / trước 08:15 | Ngừng bid và chờ tự kết thúc; hiển thị winner đúng người bid cao nhất và đơn auction liên kết. | Nếu vẫn ACTIVE/quá hạn hoặc chưa có đơn: lưu auction state, settlement/order IDs, dừng GREEN; không PATCH auction thành winner. |
| 8 / trước 09:00 | Winner mở đơn auction, xác nhận người nhận/địa chỉ và phí server tính, thấy QR/memo/total. Không bắt buộc chuyển khoản lần hai ở bước này. | Nếu chưa link hold hoặc shipping-confirmation lỗi: lưu named error; không sửa reservation/order snapshot bằng SQL. |

Endpoints hỗ trợ chẩn đoán: `GET /api/v1/orders/groups/{id}`,
`GET /api/v1/orders/{id}`, `GET /api/v1/orders/{id}/history`,
`POST /api/v1/payments` với `{"orderGroupId":"<uuid>"}`,
`GET /api/v1/auctions/{id}/state`, `GET /api/v1/auctions/{id}/bids`,
`POST /api/v1/auctions/{id}/schedule`,
`POST /api/v1/orders/{id}/shipping-confirmation` với `ShippingAddress` trực tiếp.
Sử dụng JWT và ownership đúng theo [API contract](api/openapi/commerce-service.yaml).
Các lệnh chẩn đoán không thay chứng cứ giao diện tự cập nhật.

## Nhật ký rehearsal

| Lần | Ngày/giờ Asia/Ho_Chi_Minh | Commit/URL | Người thao tác | 8 bước | Tổng thời gian | Lỗi và lần sửa |
|---|---|---|---|---|---|---|
| R1 | Chưa chạy | Chưa có STAGING | Chưa phân công | BLOCKED | Chưa đo | Chưa có môi trường |
| R2 — ngày khác R1 | Chưa chạy | Chưa có STAGING | Chưa phân công | BLOCKED | Chưa đo | — |
| R3 — ngày trước demo | Chưa chạy | Chưa có STAGING | Chưa phân công | BLOCKED | Chưa đo | — |
| Người không viết script | Chưa chạy | Chưa có STAGING | Chưa phân công | BLOCKED | Chưa đo | Ghi mọi chỗ phải đoán |

Sau mỗi lỗi: ghi bước/request ID/triệu chứng, sửa ngoài lần chạy, rồi chạy lại từ
bước 1. Chỉ đính kèm bản quay đầy đủ không cắt của một lần GREEN. Hiện chưa có
`demo/recording.mp4`; file placeholder không được coi là video nghiệm thu.

## Kiểm chứng cục bộ của phần code

Ngày 17/11/2026, Java 21: **171 unit/config tests PASS**, không failure/error/skip;
trong đó **49 ca mới** cho #158. Payment 59, Commerce 65, Auction 41, Config 6.
Tất cả chạy trên đúng source MR !198; các dependency bên ngoài được mock, Config
Data nạp YAML thật. Contract inventory: 175 operations PASS. Chưa chạy integration
DB/Kafka, deploy, seed fresh/restart hoặc chuyển khoản thật trong phiên này.

Sau khi install shared libraries từ `be-project-nexus/lib`, chạy từng lệnh tại
thư mục service tương ứng với Java 21:

```powershell
# payment-service
.\mvnw.cmd '-Dtest=DemoTransferEvidenceUnitTest,PaymentConfirmationServiceTest,SepayWebhookControllerUnitTest' test
# commerce-service
.\mvnw.cmd '-Dtest=OrderStateMachineManualPaidUnitTest,DemoManualPaidServiceUnitTest,CheckoutPaymentConfirmedConsumerTest' test
# auction-service
.\mvnw.cmd '-Dtest=AuctionServiceDemoWindowTest,AuctionPropertiesTest,AntiSnipingPolicyTest,BidServiceAntiSnipingTest' test
# config-service
.\mvnw.cmd '-Dtest=AuctionStagingConfigTest' test
```

Lượt kiểm chứng dùng Maven 3.9.16, `-Duser.timezone=UTC`, test heap 384MB/CPU2
và Mockito 5.23.0 premain qua `-DargLine` để tránh phụ thuộc self-attach trên Windows.
Nếu IDE/máy chạy cần premain, dùng JAR Mockito trong Maven cache của chính máy đó.
Unit test chứng minh validation, payload/receipt correlation, quyền và exception
propagation; rollback/concurrency DB và latency UI cần kiểm chứng riêng.
