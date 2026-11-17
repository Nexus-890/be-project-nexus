# #158 — chứng cứ SePay Live trên STAGING

Ngày chuẩn bị: 17/11/2026. **NOT RUN / BLOCKED:** chưa có URL STAGING. Không có
delivery ID, giao dịch ngân hàng hoặc latency thực tế trong tài liệu này. Unit
test, webhook tự ký và capture Test Mode không thay chứng cứ chuyển khoản thật.

## Điều kiện và cách thu chứng cứ

1. Điền URL HTTPS, commit triển khai, phiên bản config, người thao tác và người
   đối chiếu. Xác nhận SePay **Live** gửi tới
   `https://<host>/api/v1/webhooks/sepay` bằng HMAC theo [ADR-007](../adr/ADR-007-payment-provider.md).
2. Xác nhận payer Nguyễn Trường An, ngân hàng nhận VietinBank của Trần Quốc Chung,
   số tiền smoke đã thống nhất **10.000 VND/lần**. Chuẩn bị session qua checkout
   hợp lệ có đúng tổng này, tính cả shipping. Nếu tổng khác, dừng trước khi trả
   tiền và chốt lại với người phụ trách; không tự sửa amount trong DB.
3. Tạo đơn mới, lưu group/session/mọi orderId, QR memo, amount và dueAt. Để sẵn
   tab payment, quay màn hình liên tục; không bấm refresh sau khi chuyển.
4. Chuyển tiền từ ứng dụng ngân hàng thật, đúng memo/amount, trước deadline. Ghi
   `t_transfer` từ xác nhận ngân hàng và `t_ui_paid` lần đầu UI tự hiển thị PAID;
   ghi timezone và độ chính xác của đồng hồ. Latency = `t_ui_paid - t_transfer`.
5. Lấy **delivery ID** từ SePay dashboard và **transaction ID (`body.id`)** riêng:
   hai ID không mặc định giống nhau. Giữ response ACK, timestamp nhận và request
   correlation ID; che thông tin tài khoản, PII và secret trong bản đính kèm MR.
6. Đối chiếu từng DB bằng kết nối read-only của service sở hữu: Payment có receipt
   SUCCESS và session/instructions PAID; Commerce có group/orders PAID, lịch sử và
   `OrderPaid`. Không dùng ACK `{"success":true}` để suy ra đã funding.
7. Tạo **nhóm/session thứ hai**, chờ quá `dueAt` rồi mới chuyển khoản trong phạm vi
   được chủ tài khoản/payer chốt. Ghi receipt UNRECONCILED; không dùng nhóm đã PAID
   ở ca đúng hạn cho ca trễ. Deadline thực tế được code so với thời điểm server
   nhận/xử lý, không chỉ thời điểm hiển thị trên ứng dụng ngân hàng.
8. Nếu payload/headers/chữ ký hoặc hành vi khác capture cũ, giữ bản raw riêng có
   kiểm soát, ghi khác biệt và nhờ người phụ trách mở issue theo quy trình nhóm.
   Không đưa body có PII/credential vào repo.

## Lần chuyển đúng hạn

| Trường | Giá trị thực tế |
|---|---|
| URL STAGING / commit deploy | Chưa có |
| Ngày/giờ, người trả tiền, người đối chiếu | Chưa chạy |
| orderGroupId / sessionId / mọi orderId | Chưa có |
| Memo / amount / dueAt | Chưa có |
| SePay Live delivery ID / transaction `body.id` | Chưa có / Chưa có |
| t_transfer / t_webhook_received / t_commerce_paid / t_ui_paid | Chưa đo |
| transfer→PAID (UI) / độ chính xác đồng hồ | Chưa đo |
| Session/instruction/receipt / Commerce group/orders/history/outbox | Chưa kiểm chứng |
| UI tự chuyển, không refresh | Chưa kiểm chứng |
| Link ảnh/video/log đã che thông tin | Chưa có |

## Lần chuyển trễ

| Trường | Giá trị thực tế |
|---|---|
| orderGroupId / sessionId / mọi orderId | Chưa có — phải khác ca đúng hạn |
| Memo / amount / dueAt / receivedAt | Chưa có |
| SePay Live delivery ID / transaction `body.id` | Chưa có / Chưa có |
| Receipt ID / `status=UNRECONCILED` | Chưa kiểm chứng |
| Group/order không được funding bởi receipt này | Chưa kiểm chứng; có thể đã CANCELED do job hết hạn |
| Link chứng cứ / issue khác biệt so với capture | Chưa có |

Đối chiếu Payment riêng trong `payment_db`, thay session UUID thật bằng parameter
query của công cụ DB đang dùng:

```sql
SELECT id, order_group_id, total_amount, memo_code, due_at, status, paid_at
FROM payment_sessions WHERE id = :session_id;

SELECT id, session_id, txn_reference, amount, status, type, received_at, provider_occurred_at
FROM payment_transactions WHERE session_id = :session_id ORDER BY received_at, id;
```

`UNRECONCILED` là **status** receipt; type có thể là `NORMAL`. Không join sang DB
của Commerce hoặc xem ảnh thanh toán làm bằng chứng sổ cái. Lưu kết quả đã che
thông tin và link trong MR cùng delivery/order ID thật. Hiện chưa đánh dấu AC nào
về chuyển khoản thật, rehearsal hoặc video là hoàn thành.
