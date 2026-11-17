# Fallback demo #158 — chưa rehearsal

Ngày đối chiếu: 17/11/2026. **BLOCKED:** chưa có URL/orchestration STAGING, phân công,
laptop dự phòng hoặc bản quay GREEN. Tài liệu này chuẩn bị thao tác, không chứng
nhận tiêu chí fallback đã đạt. Kịch bản chính: [demo-script.md](demo-script.md).

## Vai trò và thiết bị

| Mục | Thông tin cần chốt trước rehearsal |
|---|---|
| Người thuyết trình | Chưa phân công |
| Người điều khiển máy — khác người thuyết trình | Chưa phân công |
| Người giữ Admin token | Chưa phân công; token lưu cục bộ, ngoài Git |
| Laptop thứ hai / người giữ | Chưa chuẩn bị; cần site STAGING mở sẵn, đăng nhập đúng tài khoản |
| Video toàn bộ 8 bước | Chưa có `demo/recording.mp4`; cần bản GREEN không cắt, kiểm tra phát offline |
| Auction dự phòng | Chưa tạo; cần SKU hợp lệ/stock/hold và mốc kết thúc sau ba phút tính từ giờ demo thực tế |

Trước buổi demo: mở nắp máy thứ hai và quan sát site đã tải/đã đăng nhập, kiểm tra
phiên còn hiệu lực, thử âm thanh/video offline. Không seed auction relative `now()`
ở một migration chạy từ nhiều ngày trước rồi coi nó là auction dự phòng hôm demo.
Chuẩn bị auction qua luồng nghiệp vụ ngay trước rehearsal, ghi ID/startAt/endAt và
không đưa fixture dự phòng vào kết quả GREEN của script chính.

## Chọn hướng xử lý

| Sự cố | Thao tác đã chuẩn bị |
|---|---|
| Wi-Fi máy điều khiển mất / máy ngủ | Người thuyết trình giữ mạch trình bày, người thao tác chuyển sang laptop thứ hai. Nếu server cũng mất kết nối, mở video offline và nói rõ đây là bản quay rehearsal. |
| Container chết | Người vận hành hạ tầng dùng lệnh đã chốt cho đúng stack, chờ health và kiểm tra dependency. Lần chạy có restart không tính GREEN. |
| Chuyển khoản chưa tới / UI vẫn PENDING | Ghi group/order/session/memo/delivery ID, kiểm tra Payment và Commerce riêng. Không chuyển tiền lần hai vì UI chậm. Chuyển sang video khi chưa có đường khôi phục đã rehearsal. |
| Auction/settlement chưa sẵn sàng | Dừng nhánh live tại bước lỗi, mở bản quay hợp lệ; chỉ trình bày API thay thế nếu đã thống nhất phạm vi trước buổi demo. Không ghi đủ 8 bước GREEN. |

## Restart theo stack thực tế

Repo hiện chưa có `docs/staging-runbook.md` hoặc compose STAGING chứa toàn bộ các
service. Vì vậy **chưa thể xác nhận danh sách/lệnh restart mọi container STAGING**.
Người vận hành cần xuất `docker compose ... config --services` từ deployment thật,
điền file/project/env path và kiểm tra từng lệnh trước rehearsal.

Các lệnh dưới đây chỉ áp dụng cho compose **local-dev hiện có**, chạy tại backend
root; không phải chứng cứ STAGING. Lệnh không xoá volume hoặc dựng lại database.

```powershell
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart postgres
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart kafka
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart redis
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart auth-service
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart payment-service
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart shipment-service
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart rating-service
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart kafka-ui
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart mailpit
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart zipkin
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart prometheus
docker compose --env-file infra/local-dev/.env -f infra/local-dev/compose.yaml restart grafana
```

`auth-seed` và `kafka-init` là tác vụ khởi tạo một lần; không restart chúng như
service phục vụ demo. Compose này chưa bao gồm Config, Discovery, Gateway, User,
Catalog, Commerce, Auction, Inventory, Notification hoặc Next.js; không bịa tên
container cho các tiến trình đang chạy ngoài Compose.

## PATCH manual-PAID: thao tác chẩn đoán, chưa đạt fallback AC

Code hiện tại cho phép role `ADMIN` gọi PATCH và ghi lịch sử trạng thái của **một
đơn con**. Gateway/service dùng role ADMIN; endpoint không yêu cầu privilege riêng.
JWT được chuẩn bị bằng login Keycloak thông thường, có issuer/audience hợp lệ.
Trước khi gọi, kiểm tra đúng môi trường, `orderId` và trạng thái PENDING. Không
dùng `orderGroupId` hoặc session ID vào URL sau.

Chạy trong PowerShell với `$env:STAGING_BASE_URL`, `$env:DEMO_ORDER_ID` và
`$env:DEMO_ADMIN_TOKEN` đã đặt cục bộ, không in token:

```powershell
$demoBase = $env:STAGING_BASE_URL.TrimEnd('/')
$demoHeaders = @{ Authorization = "Bearer $env:DEMO_ADMIN_TOKEN" }
$demoBody = @{ status = 'PAID'; reason = 'Issue #158 controlled fallback rehearsal' } | ConvertTo-Json
Invoke-RestMethod -Method Patch -Uri "$demoBase/api/v1/orders/$env:DEMO_ORDER_ID/status" -Headers $demoHeaders -ContentType 'application/json' -Body $demoBody
Invoke-RestMethod -Method Get -Uri "$demoBase/api/v1/orders/$env:DEMO_ORDER_ID" -Headers $demoHeaders
Invoke-RestMethod -Method Get -Uri "$demoBase/api/v1/orders/$env:DEMO_ORDER_ID/history" -Headers $demoHeaders
```

HTTP 200 trả `ApiResponse.data.status=PAID`, history có actor Admin và reason.
Không gửi lại PATCH như thao tác idempotent: PAID→PAID và chuyển trạng thái không
hợp lệ trả 409; token thiếu role trả 403; đơn không tồn tại trả 404.

**Giới hạn đã xác minh từ `OrderService.changeStatus`/`OrderStateMachine.move`:**
đường này không funding Payment session/instructions, không cập nhật trạng thái
nhóm/paidAt và không phát `OrderPaid`. Vì vậy PATCH thành công **chưa bảo đảm buyer
payment page tự chuyển hoặc toàn bộ nhóm được PAID trong 30 giây**. Không dùng
response PAID của đơn con làm bằng chứng đã thu tiền hay bằng chứng fallback AC.
Đường khôi phục đồng bộ và cách xử lý webhook đến sau cần quyết định nghiệp vụ,
triển khai và rehearsal riêng. Không sửa DB hoặc giả chữ ký webhook để che hạn chế.

## Phiếu rehearsal fallback

| Kiểm tra | Kết quả thực tế |
|---|---|
| Mở laptop thứ hai là thấy site đã đăng nhập | Chưa thử |
| Video đủ 8 bước, không cắt, phát được offline | Chưa có |
| Từng container STAGING có restart một dòng và health trở lại | BLOCKED — thiếu deployment roster |
| Order PENDING→PAID và buyer tự cập nhật trong 30 giây | BLOCKED — hạn chế group/payment ở trên |
| Ngày/giờ, commit, order/group, latency, người thực hiện | Chưa chạy |
