# Bằng chứng triển khai Reputation và Notification

Ngày kiểm tra: 19/11/2026. Nhánh
`ngothanhtu8989/hotfix/reputation-restrictions-disputes-notifications`, base
`origin/develop` tại `865cd132` sau `git fetch origin develop`.

## Phạm vi hoàn tất

- Tổng/thành công/thất bại trong summary sản phẩm, phòng đấu giá, Sales và profile.
- Restriction BID/SELL có thời hạn, lý do tạo/gỡ, audit và owner/admin reads. Backend
  chặn bid, tạo/schedule auction và publish sản phẩm ACTIVE. Sửa nháp, unpublish và
  xử lý đơn cũ vẫn dùng được. Enforcement mặc định bật; phải provision machine clients.
- Người nhận rating gửi khiếu nại; admin duyệt/từ chối. Approval ẩn rating và loại
  ảnh hưởng khỏi reputation một lần, kể cả event đến muộn/lặp, backfill và chạy đồng thời.
- Preferences Email/In app, mặc định cho user mới/cũ, giao dịch bắt buộc ở API/worker.
- BidOutbid đúng người dẫn đầu cũ; AuctionWon dùng event hiện có. Outbox/idempotency
  giữ sự kiện và dữ liệu trong cùng transaction.
- Chuông SSE riêng theo subject, cursor PostgreSQL bền vững, reconnect và logout
  cleanup. Dùng Bearer header; không thêm Web Push hoặc xin quyền push của trình duyệt.
- Admin deliveries có phân trang, bộ lọc và chi tiết attempts; owner API cũ giữ phạm vi.

Migration mới: rating `V6__restrictions_and_rating_disputes.sql` và notification
`V10__notification_preferences_and_inbox_stream.sql`. Endpoint, DTO và chính sách
gateway đã cập nhật cùng OpenAPI; FE dùng types sinh từ delivered specs.

## Kiểm tra tự động đã chạy

| Phần | Kết quả | Phạm vi |
|---|---|---|
| rating-service | PASS 154 tests | Toàn suite; native PostgreSQL 18, migration và HTTP/security, điểm/replay/backfill/concurrency; đã chạy lại 7 ca moderation sau khi dùng DTO resolve chung |
| notification-service | PASS 85 tests | 16 native PostgreSQL HTTP/preferences/visibility/admin/worker cases; template/consumer/retry/contracts/SSE expiry; VIEW không cấp quyền ghi preferences |
| auction-service | PASS 70 tests | Bid placement, outbid, guard/parser/default, schedule bị restriction sau khi tạo nháp |
| catalog-service | PASS 26 tests | HTTP SELL guard, publish DRAFT/INACTIVE, owner/delete/cost regressions |
| api-gateway | PASS 51 tests | SecurityConfigTest, gồm từ chối anonymous/sai privilege và role cho endpoint mới |
| Browser tính năng mới | PASS 7 tests | Preferences/save lỗi, chuông realtime, admin delivery filters/details, từ chối support; restriction create/revoke, dispute submit/reload và approval |
| OpenAPI generation/check | PASS | `npm run generate:types` và `npm run generate:types:check` |
| ESLint | PASS, 0 lỗi | 6 warning có sẵn trong ManualPayoutsPage/manualPayouts fixture |
| TypeScript | PASS | `npm run typecheck`, sinh route types riêng |
| Unit FE | PASS 460 tests | `npm run test:auth`, gồm service/HTTP/auth renewal/session isolation và các parser mới |
| Browser hồi quy | PASS 56 tests, 89 skipped | `npm run test:catalog`; các suite cần cấu hình riêng được skip theo thiết lập có sẵn |
| Production build | PASS | `npm run build`, output riêng `.next-reputation-validation`; đủ route mới |

Tổng cộng 386 ca backend theo các suite trong bảng, 460 unit FE và 63 ca trình duyệt
đã qua. Số ca backend là hợp của các report, không cộng trùng lượt chạy lại. Các ca
browser bị skip không được tính là đã kiểm chứng.

Các bài PostgreSQL dùng cluster tạm localhost:15439, tạo database riêng và đã dừng
cluster sau kiểm tra. Không dùng database nghiệp vụ của người dùng. Dùng JDK 21
Corretto có sẵn; JDK 25 mặc định từng làm bước compile cũ thiếu Lombok annotation
processing, nên các lượt xác minh cuối dùng JDK 21 theo yêu cầu dự án.

Surefire reports và log nằm trong `target/` của từng service. Browser mới chạy bằng:

```powershell
node scripts/test-browser.mjs --config playwright.notification-settings.config.ts --timeout 90000
```

Kết quả cuối: `7 passed`. Runner dùng Chrome thật + Next dev + HTTP/Postman stand-ins
được chỉ định; không phải toàn bộ stack microservices đang hoạt động. Suite có kiểm tra
desktop/mobile 375px và 1440px, và xuất PNG vào `test-results/notification-settings/` khi chạy.
Ca OAuth ban đầu bị HMR trong cold compilation; helper đã compile callback trước
exchange. Selector select được sửa sang combobox role; các assertion nghiệp vụ giữ nguyên.

Tóm tắt gate cơ bản được giữ trong `be-project-nexus/target/reputation-verification/frontend-base-results.json`;
log hồi quy/build nằm trong cùng thư mục. Runner catalog dùng `test-results/catalog/`
riêng để không dọn mất log/ảnh của suite khác. Fixture Cart/Product đã thêm SSE có
kiểm tra Bearer như các GET còn lại. Thời gian chờ assertion dev tăng từ mặc định 5s
lên 15s cho cold compilation; không bỏ assertion hành vi.

CLI Next.js 16.4.0 hiện có đã in cảnh báo bảo mật và dừng một lượt dev/build. Chạy lại
lệnh bình thường hoàn tất thành công; lượt này giữ nguyên dependency/lockfile. Báo cáo
ghi nhận cảnh báo của CLI, không xác nhận đã khắc phục advisory của framework.

## Kiểm tra trên hệ thống thật

Thực hiện theo [20 ca test trên giao diện](reputation-notifications-ui.md), gồm setup,
account/data, URL, bước thao tác và expected result. Đặc biệt cấu hình cả hai machine
client và cập nhật `.env` cũ trước khi bật enforcement; chạy email cần SMTP và
`EMAIL_NOTIFICATION_ENABLED=true`.

Chưa chạy toàn bộ luồng UI → gateway → Kafka → service → SMTP trên stack thật.
SMTP/Mailpit integration chưa chạy: máy không có Mailpit và Docker daemon chưa chạy;
lệnh tải/chạy Mailpit bị kiểm duyệt tự động từ chối với lý do `blocked by policy`.
Các kiểm tra mandatory delivery và optional preference trong worker đã qua PostgreSQL
thật; điều này không thay thế bằng chứng thư đã tới Mailpit.
