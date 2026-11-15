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
mới luôn cung cấp; email renderer từ chối thiếu/sai recipient và rollback marker
để retry, không đoán buyer từ actorId hoặc gửi thiếu bidder.

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

`NotificationTemplateTest` kiểm tra nội dung từng template, recipient scalar/mảng,
distinct, mảng bidder rỗng, thiếu/sai recipient, link đánh giá và tracking đang chờ.
`DomainEventConsumerTest` kiểm tra fan-out, các topic mới và event trùng.
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
   Tạo shipment và thực hiện trạng thái delivered bằng luồng #202/#203; buyer có
   email shipped với carrier/tracking và email delivered với link tới order để rate.
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
   `ORDER_REFUND_REQUESTED`; channel là `EMAIL`), rồi tạo event mới. Notification vẫn có trong history, delivery
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

## Kết quả local ngày 15/11/2026

Đã chạy trên source #259 tách riêng từ develop `aa238638`, bằng JDK 21 và các
fixture tích hợp PostgreSQL/Kafka/Mailpit. Cả năm service build/verify thành công:

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
Đây là kết quả local trên source feature, không phải remote CI. Chưa chạy manual
T3 qua provider thanh toán/carrier thật tới Mailpit và không xác nhận publisher
AuctionPaymentTimeout #208 đã tích hợp. Không đánh dấu các phần đó đã nghiệm thu.
