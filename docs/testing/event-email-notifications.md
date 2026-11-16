# Kiểm chứng email theo domain event — #259

Issue mở rộng pipeline email hiện có cho thanh toán, shipment, refund và auction.
Không thêm endpoint. `DomainEventConsumer` giữ COMMERCE/CATALOG, bổ sung PAYMENT,
SHIPMENT và AUCTION; bảng đầy đủ nằm trong [event catalogue](../api/events.md#event-email-notifications-259).

## Nguồn và phạm vi

Đã đối chiếu ngày 15/11/2026 với SRS local v1.0 (revision 04/06/2026), phần
§3.2.3 Notification và §5.2–5.3; endpoint catalogue local `Endpoints!A41:X47`,
`Jobs & consumers!A9:J12`; ADR-008 và API style guide. Catalogue dùng mã
NTF-01/NTF-02 cho hai endpoint đọc. Nhãn §3.7 / NOT-01 / NOT-02 trong issue không
phải số mục/mã của hai file local này.

Yêu cầu #259 cho phép tắt từng loại EMAIL và ghi SKIPPED; đây là quyết định áp dụng
cho task này, thay cho câu cũ trong SRS/catalogue rằng email order/payment bắt buộc.
Không làm preferences UI/history, SMS hoặc Outbid. Tại lần đọc GitLab ngày
15/11/2026, #172 và #208 còn mở: test hợp đồng có thể dùng payload mẫu, nhưng không
được coi đó là xác nhận publisher hoặc luồng winner/timeout đã tích hợp hoàn chỉnh.

CheckoutPaymentConfirmed và hai refund event thực tế do payment-service phát trên
`nexus.payment.events`; không chuyển chúng sang COMMERCE theo mô tả cũ của issue.
Refund thêm buyerId từ PaymentInstruction.buyerId local. AuctionCancelled thêm bidderIds
distinct từ bids local trong cùng transaction giữ lock auction. Các field mới
không thêm vào `required` của schema v1 để giữ khả năng đọc event lịch sử. Publisher
mới luôn cung cấp. Với refund lịch sử thiếu hoặc null buyerId, consumer hoàn tất
processed marker, không tạo notification/delivery và không đoán buyer từ actorId
hay orderId. Với AuctionCancelled lịch sử thiếu hoặc null bidderIds, consumer chỉ
gửi một lần cho sellerId hợp lệ bắt buộc. Replay hai dạng cũ này không gửi thêm.
Recipient có giá trị nhưng sai UUID hoặc bidderIds không phải mảng vẫn bị từ chối
trước khi lưu bất kỳ recipient nào và rollback marker để retry.

Review ngày 16/11/2026 tại `53409a13` xác nhận ShipmentCreated được phát lúc
PENDING_TRACKING với trackingCode=null. Việc carrier cấp tracking sau đó không
phát lại ShipmentCreated. Vì vậy email shipped dùng ShipmentPickedUp với carrier
và trackingCode đã có; ShipmentCreated không tạo notification. Notification type
và EMAIL preference vẫn giữ `SHIPMENT_CREATED` đã lưu trước đây, để các lựa chọn tắt email hiện có tiếp tục áp dụng khi ShipmentPickedUp kích hoạt gửi.

## Test tự động

Dùng JDK 21, build shared libraries theo source đang kiểm tra. Chạy trong
`be-project-nexus`; lệnh là hướng dẫn tái hiện, không phải kết quả đã chạy:

```powershell
Push-Location lib
.\mvnw.cmd '-Duser.timezone=UTC' install
Pop-Location
Push-Location notification-service
.\mvnw.cmd '-Duser.timezone=UTC' clean verify
Pop-Location
Push-Location payment-service
.\mvnw.cmd '-Duser.timezone=UTC' verify
Pop-Location
Push-Location commerce-service
.\mvnw.cmd '-Duser.timezone=UTC' verify
Pop-Location
Push-Location auction-service
.\mvnw.cmd '-Duser.timezone=UTC' verify
Pop-Location
node docs/api/check-contract.mjs
```

Chạy riêng regression của hai lỗi review, không cần Docker, trên checkout có
publisher Shipment #202/#203 như base MR `53409a13`:

```powershell
Push-Location shipment-service
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=ShipmentNotificationPublisherTest' test
Pop-Location
Push-Location notification-service
.\mvnw.cmd '-Duser.timezone=UTC' '-Dtest=NotificationTemplateTest,DomainEventConsumerTest,ShipmentNotificationPublisherContractTest' test
Pop-Location
```

`NotificationTemplateTest` kiểm tra nội dung từng template, recipient scalar/mảng,
distinct, mảng bidder rỗng, payload lịch sử thiếu field additive, recipient sai,
link đánh giá và yêu cầu tracking của ShipmentPickedUp.
`DomainEventConsumerTest` kiểm tra fan-out, các topic mới, event trùng và replay
refund/AuctionCancelled v1 rồi tiếp tục xử lý event hợp lệ kế tiếp. Payload shipment
để kiểm email cần đối chiếu với outbox từ publisher thật; fixture SMTP tự gán
tracking không chứng minh luồng tạo shipment có phát event mang tracking.
`ShipmentNotificationPublisherTest` kiểm tra payload từ service và outbox writer
production với JDBC boundary được mock, đối chiếu fixture chung
`notification-service/src/test/resources/shipment-notification-publisher.json`.
`ShipmentNotificationPublisherContractTest` dùng đúng fixture này qua notification
consumer để kiểm creation không gửi, pickup gửi carrier/tracking và replay không
gửi thêm. Hai phía cùng ghim fixture nên payload publisher đổi sẽ làm regression
thất bại; đây là unit test hợp đồng, không phải nghiệm thu JDBC/SMTP hoặc T3.
Kiểm chứng chống trùng/rollback bằng `processed_events` phải dùng PostgreSQL thật;
test mock đơn thuần không chứng minh transaction hoặc UNIQUE constraint hoạt động.
Email preference tests phải bao phủ tắt trước khi queue, tắt trước retry, giữ
SKIPPED và không gọi directory/SMTP. Các test publisher kiểm tra refund có buyerId,
auction có mỗi bidder đúng một lần và dữ liệu được ghi trong transaction hiện có.

Ghi kết quả thực tế riêng theo từng service, số test, skip, môi trường và log. Nếu
Docker/PostgreSQL/Kafka/Mailpit không chạy, ghi đúng phần chưa kiểm chứng; không
suy ra full verify, remote CI hoặc manual acceptance từ unit test.

## Manual với Mailpit

1. Chạy PostgreSQL, Kafka/topics, Keycloak và Mailpit của local stack, sau đó các
   service liên quan. Bật `EMAIL_NOTIFICATION_ENABLED=true`; SMTP mặc định
   `localhost:1025`, Mailpit UI [localhost:8025](http://localhost:8025).
   `KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET` cần khớp client notification-service
   hiện có; không đưa secret hoặc địa chỉ email thật vào evidence. Đặt
   `NEXUS_FRONTEND_URL` đúng frontend dùng thử để link đánh giá mở được.
2. Dùng buyer/seller/bidder thử nghiệm có email xác định trong Keycloak. Tạo order
   mới, thanh toán đúng tổng qua luồng đang có, xác minh một email Payment received
   cho buyer của cả group, đúng tổng/currency; seller không nhận email thanh toán.
   Tạo shipment bằng luồng #202, đối chiếu ShipmentCreated PENDING_TRACKING/null
   tracking không tạo email shipped. Sau khi carrier đã cấp tracking, chuyển sang
   trạng thái `SHIPPED` qua luồng #203 (publisher phát `ShipmentPickedUp`) và đối chiếu email shipped với carrier/tracking trong
   chính outbox ShipmentPickedUp. Tiếp tục DELIVERED và kiểm email delivered với
   link tới order để rate. Replay nguyên ShipmentPickedUp phải giữ đúng một email.
3. Với fixture khác, chạy shipment failed/returned và refund requested/completed.
   Đối chiếu email với buyer, order, amount, currency và reason trong outbox; không
   coi email Refund requested là bằng chứng chuyển tiền hoàn đã hoàn tất.
4. Tạo auction có ít nhất hai bidder, một bidder đặt nhiều bid. Admin cancel bằng
   API hiện có. So sánh bidderIds trong outbox với các bidder distinct đã bid;
   mỗi bidder và seller có đúng một email Auction cancelled, đúng reason. Admin
   chỉ nhận nếu đồng thời là một recipient đã nêu trong payload.
5. Sau khi #208 tích hợp, để auction order quá paymentDueAt theo luồng timeout thật;
   winner và seller có đúng một email, losing bidders không nhận. Payload mẫu chỉ
   kiểm tra consumer, không thay cho bước tích hợp publisher này.
6. Replay nguyên envelope đã lưu trên đúng topic, giữ eventId. Xác minh số
   notifications/deliveries, processed marker và số email Mailpit không tăng.
   Ghi nhận log/DB sau khi consumer đã xử lý replay, tránh kết luận từ việc chờ
   chưa đủ lâu. Không sinh eventId mới để thử AC duplicate envelope.
7. Trên tài khoản thử nghiệm, đặt EMAIL preference của đúng notification type
   thành disabled (tên enum lưu trong `notifications.type`, ví dụ
   `ORDER_REFUND_REQUESTED` hoặc `SHIPMENT_CREATED`; channel là `EMAIL`), rồi tạo event mới. Notification vẫn có trong history, delivery
   là SKIPPED, attempt SMTP không tăng và Mailpit không có email cho user đó.
   Với auction nhiều recipient, các user còn bật vẫn nhận. Kiểm tra thêm tắt
   preference khi delivery đang chờ retry, sau đó khôi phục giá trị thử nghiệm.

Preference không tồn tại mặc định là enabled. Nếu tắt trước queue, delivery có
attempt_count=0 và next_attempt_at=null; nếu tắt khi đang chờ retry, giữ lịch sử
attempt cũ và không bắt đầu attempt mới. Có recheck ngay trước SMTP: nếu preference
bị tắt trong lúc tra Keycloak sau khi claim, delivery chuyển SKIPPED và attempt
đã bắt đầu được đóng FAILED/PREFERENCE_DISABLED, retryable=false (schema attempt
hiện có không có trạng thái SKIPPED); email không được gửi.

Các bước trên chưa tự chứng minh đã chạy thành công. Chỉ đánh dấu T3/AC email khi
quan sát email Mailpit và đối chiếu recipient/trạng thái DB của cùng event thật.

## Kết quả lịch sử ngày 15/11/2026, trước sửa review

Kết quả dưới đây thuộc source #259 trước sửa hai lỗi review tại `53409a13`, tách
riêng từ develop `aa238638`, bằng JDK 21 và fixture PostgreSQL/Kafka/Mailpit.
Các fixture email này không kiểm chứng tracking từ publisher Shipment thực tế
hoặc replay refund/AuctionCancelled v1 qua notification consumer. Cả năm service
build/verify lúc đó thành công:

| Service | Số test | Failure / error | Skip |
| --- | ---: | --- | ---: |
| notification-service | 156 | 0 / 0 | 0 |
| payment-service | 279 | 0 / 0 | 3 provider-capture có sẵn, thiếu dữ liệu capture bên ngoài |
| commerce-service | 459 | 0 / 0 | 5 Catalog simulation có sẵn, thiếu URL dịch vụ mô phỏng |
| auction-service | 295 (285 Surefire + 10 Failsafe) | 0 / 0 | 0 |
| config-service | 7 | 0 / 0 | 0 |

Notification integration dùng consumer, processed_events, transaction PostgreSQL,
delivery worker và SMTP Mailpit thật; tra recipient và JWT là fixture. Đã kiểm tra
email của chín event thuộc tám dòng issue, replay trước/sau SMTP, bidder distinct,
fan-out lỗi rollback toàn bộ rồi retry, preference theo từng recipient và tắt
trước queue/trước retry/trong lúc tra recipient. Bốn PostgreSQL cancellation cases
kiểm tra snapshot bidder, mảng rỗng, hủy lặp và rollback khi outbox lỗi. Refund
publisher và Commerce compatibility tests kiểm tra buyerId mới và payload v1 cũ.

API inventory checker đạt 103 operations. Ajv 6.15.0 kiểm tra Draft7 của ba schema
đổi payload: 11 kiểm tra đạt cho example mới, historical thiếu field additive,
UUID sai, bidder rỗng và bidder trùng. Audit diff chỉ có 26 file #259, không thêm
endpoint, migration hoặc source của các task khác; `git diff --check` đạt.

Log tái hiện tại `D:\GitLab\log\email259-{notification,payment,commerce,auction,config}-verify.log`.
Đây là kết quả local lịch sử trên source feature, không phải kết quả cho code sửa
review hoặc remote CI. Chưa chạy manual
T3 qua provider thanh toán/carrier thật tới Mailpit và không xác nhận publisher
AuctionPaymentTimeout #208 đã tích hợp. Không đánh dấu các phần đó đã nghiệm thu.
