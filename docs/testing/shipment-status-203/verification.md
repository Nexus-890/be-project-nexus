# Xác minh bộ Postman #203

Kiểm tra lại ngày **12/11/2026** sau khi tích hợp #206: **102 request, 483 assertion, 0 lỗi**. SQL đạt **36 case và 5 kiểm tra consumer**. Collection hiện tại giữ `/tracking` tổng hợp cho FE và dùng `/history` cho audit phân trang. Companion kiểm tra các operation bắt buộc qua contract checker, không cố định tổng số endpoint.

Log hiện tại: `D:/KyOJT/tmp/issue203-resume-20261112/postman-verified.log` và `sql-evidence.csv`. Chi tiết backend/FE và giới hạn browser: [báo cáo 12/11](../shipment-status-203-review-2026-11-12.md).

Các kết quả bên dưới là lượt xác minh trước khi đồng bộ #206:

Ngày chạy: 11/11/2026, Asia/Saigon.

| Kiểm tra | Kết quả |
| --- | --- |
| Collection thực thi qua Postman CLI | 101 request, 0 lỗi request |
| Assertion Postman | 480 assertion, 0 lỗi |
| Test scripts | 101, 0 lỗi |
| Pre-request scripts | 151, 0 lỗi |
| SQL `02-evidence.sql` | 36 case đạt `case_pass=true` |
| SQL marker/tombstone | 5 ca đạt `consumer_pass=true` |
| SQL `03-cleanup.sql` | Không còn shipment/outbox/tombstone fixture sau cleanup |
| Environment bàn giao | Chỉ 4 token trống; không chứa credential đăng nhập đã cấp |
| Kiểm tra cuối với Keycloak thật và environment trống | Folder Setup: 2 request, 4 assertion đạt; tracking không có token trả 401 |
| Mapping trạng thái GHN | 23 trạng thái được kiểm tra độc lập |
| Kafka | Kiểm tra event thật, key/orderId và envelope khớp outbox |
| Log Support | Đếm nội dung WARN thật theo orderId/eventId, đúng một lần |

Môi trường sử dụng shipment-service thật ở cổng 18088, PostgreSQL 18 và Kafka 4.3.1 trong hai container riêng `issue203-postgres`, `issue203-kafka`. API phía GHN được mô phỏng tại cổng 9903. Không có lời gọi GHN sandbox hoặc thay đổi dữ liệu của môi trường ứng dụng đang chạy.

Lượt tự động dùng khóa RSA/JWT tạm trên service kiểm thử để kiểm tra toàn bộ collection, gồm phân quyền và ownership. Không login bằng tài khoản của người dùng. Sau lượt chạy, khóa/JWT tạm được xóa, service được khởi động lại với issuer/JWKS Keycloak local thật, và dữ liệu được reset để người dùng tự login rồi chạy chụp evidence.

Kết quả thực thi local nằm trong `evidence/postman-final-validation.log`, `evidence/sql-validation.txt`, `evidence/cleanup-validation.txt`. Các file này được gitignore. Ma trận AC và nội dung từng ảnh cần chụp nằm trong [README.md](README.md#5-ma-trận-ac--testcase--ảnh-evidence).
