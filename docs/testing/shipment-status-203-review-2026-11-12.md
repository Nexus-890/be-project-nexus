# Kiểm tra lại nhánh #203 — 12/11/2026

Worktree: `D:/KyOJT/worktrees/issue203`. Nhánh `ngothanhtu8989/feature/203-be-shipment-status-webhook-poller-manual-update`, HEAD `e6f62630`, đã tích hợp develop có #202 và #206 (`8a2ff658`). Các thay đổi #203 vẫn chưa commit; kiểm tra bao gồm cả staged, unstaged và file mới.

## Các thay đổi đã xác minh

- Seller ownership lấy từ `OrderPaid.sellerId` hoặc snapshot commerce tin cậy, lưu bằng migration V4; dữ liệu `shippingInfo` không cấp quyền. Seller khác không đọc được shipment. Shipment cũ chưa có seller tin cậy vẫn bị ẩn với seller.
- PATCH thiếu reason lưu lý do audit mặc định; override vẫn yêu cầu reason. MANUAL event đáp ứng contract consumer commerce.
- Carrier observation cùng trạng thái cập nhật mốc thời gian mà không thêm business version/history/outbox; cập nhật cũ không mở lại trạng thái trước đó.
- Commerce xử lý tiến trình sau pickup và delivery thiếu event pickup; delivery từ PAID ghi chuỗi SHIPPED, DELIVERED, COMPLETED trong transaction. Duplicate không lặp completion.
- Toàn bộ `Shipment*` event dùng `orderId` làm Kafka key. Hồi quy gồm resolver và Kafka khi thiếu pickup.
- Giữ `GET /shipments/{id}/tracking` của #206 với object tổng hợp. Audit phân trang ở `GET /shipments/{id}/history`, có quyền gateway tương ứng.

Trong lượt tiếp tục, đã sinh lại collection Postman từ builder thành 102 request. Companion được sửa để kiểm tra năm operation bắt buộc cùng contract checker, thay cho điều kiện cố định 11 operation đã lỗi thời.

## Kết quả

| Kiểm tra | Kết quả |
| --- | --- |
| Maven verify shipment-service | 452 đạt, 0 failure/error/skip |
| Maven verify commerce-service | 433 đạt, 5 catalog opt-in skip, 0 failure/error |
| Maven verify config-service | 7 đạt |
| Maven verify api-gateway | 82 đạt |
| API contract checker | 98 operation khớp controller/OpenAPI; shipment có 12 |
| OpenAPI lint shipment + commerce | Đạt; commerce có 4 cảnh báo path đã có |
| FE generated types drift | Đạt |
| FE lint, typecheck | Đạt |
| FE unit | 259/259 đạt |
| FE production build bằng Webpack | Đạt, dùng output riêng |
| Browser Orders | Lượt đầy đủ 20/22 đạt; retry có biên dịch sẵn route khôi phục thêm 1 ca, còn 1 timeout đăng nhập |
| Browser FE chung | 51 đạt, 4 timeout, 22 ca Orders bỏ qua có chủ đích |
| Postman collection hiện tại | 102 request, 483 assertion, 0 lỗi |
| SQL evidence | 36/36 case và 5/5 consumer check đạt |
| Schema/ví dụ event | 106 JSON block hợp lệ; hai schema #203 qua 28 ca thiếu field và 6 ca UUID/time/status sai |
| Setup sau khi trở về Keycloak thật | 2 request, 4 assertion đạt; tracking ẩn danh trả 401 |

Báo cáo Maven và lint/typecheck/build của lượt trước được đối chiếu trực tiếp trong log và XML tại worktree; không chạy lại các suite đã đạt khi chỉ sửa companion và artifact Postman. Unit, generated types drift, browser và collection được thực thi lại trong lượt tiếp tục.

## Browser Orders

Suite đầy đủ chạy 22 ca: 20 đạt; 2 timeout tại đăng nhập fixture hoặc chuyển trang. Khi biên dịch sẵn route Next dev, ca chuyển sang thanh toán đạt lại với nguyên assertion và timeout.

Ca `returned shipment remains readable without periodic requests` vẫn timeout 5 giây khi chờ heading `Orders` sau callback đăng nhập. Ảnh lỗi còn ở trang `Signing you in`; chưa tới bước đọc shipment hay kiểm tra polling. Không thay assertion, ngưỡng chờ hoặc logic sản phẩm để làm test đạt.

Frontend runtime và `tests/orders.spec.ts` không đổi so với HEAD; diff FE của #203 chỉ gồm hai file generated types. Suite này dùng HTTP fixture, không gọi backend #203. Vì vậy chưa có bằng chứng lỗi trả trạng thái RETURNED từ backend; cũng chưa thể báo toàn bộ browser gate đạt.

## Browser FE chung

Suite chung hoàn tất với 51 ca đạt, 4 ca lỗi và 22 ca Orders bỏ qua theo thiết kế. Bốn lỗi đều hết thời gian chờ 5 giây:

- `buyer-shop-navigation.spec.ts:11`: điều hướng mobile 375px từ Profile sang Sign in.
- `buyer-shop-preview.spec.ts:3`: chờ heading đơn mẫu chưa thanh toán.
- `catalog.spec.ts:49`: chờ điều hướng vào category electronics.
- `product-images.spec.ts:24`: chờ đăng nhập xong để hiển thị Product images.

Các source/test trên không thay đổi trong diff #203. Không sửa các luồng FE ngoài phạm vi shipment hoặc tăng timeout để làm xanh bộ kiểm tra. Log chi tiết ở `browser-general-final.log`; browser gate còn lỗi và không phải bằng chứng đã sẵn sàng merge mọi phần của FE.

## Phạm vi bằng chứng

Postman chạy với shipment-service, PostgreSQL và Kafka thật trong môi trường riêng #203; GHN là mock local. JWT RSA/JWKS tạm phục vụ kiểm tra role, privilege và ownership. Đây không phải evidence GHN sandbox hay đăng nhập Keycloak bằng tài khoản người dùng.

Log lần tiếp tục ở `D:/KyOJT/tmp/issue203-resume-20261112/`: `postman-verified.log`, `sql-evidence.csv`, `frontend-unit-final.log`, các log `browser-orders-*` cùng ảnh/context được lưu riêng. Log Maven tổng ở `D:/KyOJT/tmp/issue203-full-verify.log`; log build FE ở `D:/KyOJT/tmp/issue203-fe-build-webpack-retry.log`.

Postman environment bàn giao giữ bốn token trống. Người dùng tự login, import collection/environment trong `shipment-status-203/postman`, chạy Setup rồi các folder nghiệp vụ. Kết quả 101 request/480 assertion ngày 11/11 trong tài liệu cũ là lịch sử trước khi đồng bộ #206.

Sau lượt xác minh, kit đã khởi động lại với issuer/JWKS Keycloak thật, fixture được seed lại, JWKS/JWT tạm đã dừng/xóa. Tracking không có bearer trả 401.

Cấu hình Playwright tạm dùng Webpack và script biên dịch sẵn route được lưu trong thư mục log, sau đó gỡ khỏi source FE. Không commit hoặc push thay đổi trong lượt tiếp tục này.
