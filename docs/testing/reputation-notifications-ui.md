# Kế hoạch kiểm thử Reputation và Notification trên giao diện

Nhánh: `ngothanhtu8989/hotfix/reputation-restrictions-disputes-notifications`.
Ngày lập: 19/11/2026. Đây là kế hoạch kiểm thử thủ công trên hệ thống thật; kết quả
unit/integration và trình duyệt dùng fixture được ghi riêng trong báo cáo verification.

## Chuẩn bị một lần

1. Chạy infrastructure, Keycloak, Config, Discovery, Gateway, user, catalog, commerce,
   inventory, auction, rating và notification services từ cùng nhánh. Chạy payment/shipment
   khi kiểm tra hoàn tất đơn hoặc quá hạn thanh toán. Flyway tự áp dụng rating V6 và
   notification V10; không sửa migration đã chạy bằng tay.
2. Giữ `FEATURE_REPUTATION_ENFORCEMENT=true` và
   `FEATURE_CATALOG_REPUTATION_ENFORCEMENT=true`. Nếu `.env` cũ đang đặt `false`, đổi
   rồi khởi động lại dịch vụ. Với cấu hình mặc định mới, rating-service hoặc machine
   credential không hoạt động sẽ làm thao tác bán/bid báo không khả dụng.
3. Cấu hình secret của `nexus-auction-service` và `nexus-catalog-service` trong `.env`
   cục bộ, chạy bootstrap theo [hướng dẫn machine client](../../docs/security-keycloak/rating-eligibility.md).
   `RATING_ELIGIBILITY_ALLOWED_CLIENTS` phải có cả hai client. Không dán secret/token
   vào ảnh chụp, checklist hoặc commit.
4. Để kiểm tra email: bật `EMAIL_NOTIFICATION_ENABLED=true`, cấu hình SMTP tới Mailpit
   hoặc hộp thư thử nghiệm; mở Mailpit theo cổng trong `.env` (mặc định trang 8025).
   Email bị tắt toàn cục không chứng minh được tính năng gửi/retry.
5. Chạy FE với gateway đã cấu hình trong `.env.local`. Mở địa chỉ FE thực tế (thường
   `http://localhost:3000`), kiểm tra đăng nhập và tải sản phẩm trước khi test.

## Tài khoản và dữ liệu

| Vai trò | Sử dụng |
|---|---|
| ADMIN | Thêm/gỡ hạn chế, xử lý khiếu nại và xem deliveries |
| SELLER S | Có sản phẩm DRAFT đầy đủ SKU/tồn kho, một sản phẩm ACTIVE, auction nháp và auction đang diễn ra |
| BUYER A | Đấu giá, đặt đơn, đánh giá seller |
| BUYER B | Đấu giá vượt BUYER A; dùng tài khoản thứ hai riêng biệt |
| SUPPORT_STAFF | Kiểm tra trang quản trị mới không cấp quyền nhầm |

Dùng browser profile khác nhau cho từng người, hoặc cửa sổ riêng có cookie độc lập.
Seed hiện có có thể chỉ tạo một buyer; tạo tài khoản BUYER B bằng đăng ký/admin UI,
xác minh email và đảm bảo đủ điểm BID. Dùng tài khoản thử nghiệm; lấy mật khẩu từ
cấu hình đang có, không dùng mật khẩu minh họa trong test fixture.

Chuẩn bị một đơn `COMPLETED` của BUYER A mua từ SELLER S để đánh giá, hai auction
cho hai người thắng khác nhau, và một tài khoản có ít nhất một giao dịch thất bại.
Nếu chỉ có dữ liệu mới hoàn toàn, các số 0 là hợp lệ nhưng chưa kiểm tra được bộ đếm.
Ghi điểm và số giao dịch trước mỗi ca; cập nhật reputation qua event có thể đến sau
request một khoảng ngắn.

## Thực hiện theo thứ tự

| ID | Người dùng, đường vào và thao tác | Kết quả cần đạt |
|---|---|---|
| UI-01 | Buyer đăng nhập, mở chi tiết sản phẩm của S và phòng đấu giá của S. Seller mở Back office → Sales. Mở My profile → Reputation. | Khối reputation hiển thị tổng, thành công, thất bại từ cùng dữ liệu người dùng; không có số minh họa cố định. Seller Sales hiển thị summary của seller. |
| UI-02 | Hoàn tất một đơn hợp lệ; chờ cập nhật rồi tải lại các trang UI-01. | Tổng/thành công tăng đúng cho buyer và seller; không tăng lặp khi tải lại. Với winner quá hạn thanh toán, tổng/thất bại tăng theo quy tắc hiện có. |
| UI-03 | Admin → Reputation (`/backoffice/reputations`), tìm tên/email A, chọn Manage reputation. Thêm BID, thời gian tương lai, lý do rõ ràng. | Báo Restriction added; danh sách có action, lý do, hạn, trạng thái Restricted. Không được lưu thời gian quá khứ hoặc lý do trắng. |
| UI-04 | A có điểm cao hơn ngưỡng, mở auction của S và đặt giá. | Bị chặn bằng thông báo có lý do/thời hạn; không thêm bid hợp lệ, không đổi giá/người dẫn đầu. A vẫn đăng nhập, xem sản phẩm và dùng các thao tác không bị hạn chế. |
| UI-05 | Admin chọn Remove restriction, nhập lý do gỡ và xác nhận. A thử bid lại. Lặp lại với restriction ngắn rồi để tự hết hạn. | Gỡ giữ lịch sử/reason, trạng thái Revoked; hết hạn thành Expired khi tải lại. Bid hợp lệ hoạt động sau gỡ/hết hạn nếu đạt các điều kiện khác. |
| UI-06 | Admin thêm SELL cho S. S thử publish sản phẩm DRAFT/INACTIVE, tạo auction mới và schedule auction nháp có trước khi bị khóa. | Các thao tác bán mới bị chặn, có lý do/thời hạn. S vẫn sửa nháp, hủy đăng bán và xử lý đơn cũ được. BID của S không bị khóa chỉ vì restriction SELL. |
| UI-07 | Tạo hai restriction BID có hạn khác nhau; gỡ một restriction nhưng giữ restriction còn hiệu lực. | A vẫn bị chặn đến khi tất cả restriction BID đang hiệu lực được gỡ/hết hạn. |
| UI-08 | A đánh giá đơn COMPLETED của S từ trang đơn hàng. S → My profile → View ratings received (`/ratings`), chọn Dispute rating, nhập lý do, Submit dispute. | Chỉ người nhận đánh giá gửi được khiếu nại. Sau thành công và tải lại, trạng thái PENDING và nút không gửi lặp. Trống lý do không được gửi. |
| UI-09 | Admin → Rating disputes (`/backoffice/rating-disputes`) → Pending → Review dispute → Approve and hide rating, nhập Decision reason, Save decision. | Khiếu nại rời Pending, xuất hiện ở Approved với lý do. Rating không còn trong danh sách thường; ratingCount/score tính lại đúng, giữ số giao dịch. Tải lại không trừ ảnh hưởng hai lần. |
| UI-10 | Với rating khác, admin chọn Reject and keep rating, nhập lý do. Seller xem lại `/ratings`. | Rating còn hiển thị, trạng thái REJECTED; điểm không bị thay đổi vì quyết định từ chối. Không xử lý lại một khiếu nại đã có quyết định. |
| UI-11 | Tài khoản cũ và tài khoản mới → Account → Notifications (`/notifications`). | Có ma trận loại × Email/In app; mặc định bật. Các loại giao dịch có Required for your transactions và checkbox bị khóa. |
| UI-12 | Tắt BID_OUTBID Email, giữ In app; Save preferences, tải lại. Dùng B vượt giá của A. Sau đó kiểm tra ngược lại: bật Email, tắt In app và tạo sự kiện vượt giá mới. | Cài đặt giữ sau tải lại. Kênh bị tắt không nhận sự kiện mới; kênh bật vẫn nhận. Sự kiện trước đó không bị xóa. Giao dịch bắt buộc luôn bật ở backend. |
| UI-13 | Trên auction ACTIVE: A dẫn đầu, B đặt bid lớn hơn. Sau đó B tự nâng giá của mình; A dùng Retry same bid sau lỗi mạng nếu có. | A nhận thông báo vượt giá, B tự nâng giá không nhận thông báo vượt chính mình; retry không tạo thông báo lặp. Sự kiện chỉ xuất hiện cho bid được chấp nhận. |
| UI-14 | Để auction kết thúc với winner hợp lệ; không chỉ nhìn đồng hồ về 0, chờ settlement hoàn tất. | Winner có thông báo thắng và email khi SMTP bật; người thua không nhận thông báo thắng. Dùng lại chuông, không yêu cầu màn riêng. |
| UI-15 | Giữ trang buyer mở trong lúc B vượt giá/auction kết thúc. Không F5; quan sát badge và mở chuông. | Badge/list cập nhật realtime. SSE không cần trình duyệt hỏi quyền push. Đóng trang thì không hứa nhận Web Push. |
| UI-16 | Ngắt mạng ngắn rồi nối lại; sau đó logout A và login B trong cùng browser profile. Giữ một profile A khác để so sánh. | Kết nối khôi phục và đồng bộ thông báo; không lộ inbox/badge của A cho B. Tải lỗi có thông báo và thao tác refresh. Token không nằm trên URL. |
| UI-17 | Admin → Notification deliveries (`/backoffice/notification-deliveries`). Lọc status FAILED/PENDING/SENT, channel Email, thời gian và mã lỗi. Chọn một delivery. | Danh sách/phân trang đúng bộ lọc; chi tiết có attempt number, status, thời điểm, errorCode/retryable. Không lộ địa chỉ email nhận, body thư hoặc credential SMTP. |
| UI-18 | Trong môi trường thử nghiệm, dừng SMTP tạm thời, tạo một thông báo Email hợp lệ; xem deliveries, bật SMTP trở lại và chờ retry. | Có lần gửi FAILED retryable, delivery PENDING và thời gian retry; sau SMTP hồi phục có attempt mới và SENT. Không cần nút retry thủ công vì worker tự thực hiện. |
| UI-19 | Buyer, Seller, Support thử mở ba URL quản trị mới; logout rồi mở các URL đó. | Không có quyền thì Access denied/đăng nhập; không tải dữ liệu admin. Admin đọc được deliveries người khác qua trang admin; API owner cũ vẫn chỉ đọc của chủ. |
| UI-20 | Tại các trang mới, thử viewport 375px và desktop; điều hướng bằng Tab, thử backend lỗi tạm thời khi lưu. | Form có label/focus; thông báo lỗi rõ, không báo thành công giả, giữ nội dung cần sửa. Không tràn ngang toàn trang; bảng có thể cuộn trong khung. |

## Ghi kết quả

Mỗi ca ghi: PASS/FAIL/BLOCKED, tài khoản, giờ Việt Nam, URL, dữ liệu trước/sau,
ảnh trước/sau và thông báo lỗi. Không lưu token/mật khẩu vào bằng chứng. Với lỗi,
ghi bước nhỏ nhất tái hiện được; khi cần mở DevTools chỉ chụp method/path/status,
che Authorization và cookie.

Ưu tiên chạy UI-01, 03–06, 08–09, 11–15, 17, 19 trước; sau đó chạy hết các ca còn
lại. Các kiểm thử replay/concurrency/backfill có integration test tự động riêng;
test thủ công tập trung hành vi nhìn thấy trên giao diện và đối chiếu số trước/sau.
