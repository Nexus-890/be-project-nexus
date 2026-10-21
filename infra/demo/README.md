# Chạy thủ công với API và PostgreSQL thật

FE gọi backend Spring thông qua gateway `http://localhost:8080`. Các nhánh
mock sản phẩm/giỏ hàng, auth stub và Postman profile server giả đã được xóa.

## Tự khởi động service

Docker infrastructure phải healthy. Mở mỗi service trong một terminal riêng,
chuyển vào thư mục service rồi chạy ` .\mvnw.cmd spring-boot:run` theo thứ tự:

| Thư mục trong `be-project-nexus/` | Port |
|---|---|
| `config-service` | 8888 |
| `discovery-service` | 8761 |
| `user-service` | 8081 |
| `catalog-service` | 8082 |
| `commerce-service` | 8083 |
| `api-gateway` | 8080 |

Chờ Config Server khởi động trước các service còn lại. Khi chạy trong IDE,
đặt working directory là thư mục service để Spring đọc được file
`../infra/local-dev/.env`. Discovery đã được bổ sung test dependency để lệnh
Maven thông thường không vướng lỗi compile test.

Trong terminal FE:

```powershell
cd fe-project-nexus
npm run dev
```

Mở **http://localhost:3000**. `.env.local` dùng `NEXT_PUBLIC_API_URL=` và
`API_PROXY_TARGET=http://localhost:8080`; không có cờ mock. PostgreSQL/Kafka/Redis,
file `infra/local-dev/.env`, JWT key paths và Node dependencies phải có sẵn như
môi trường phát triển hiện tại.

Đã dừng các process kiểm tra của Codex trước khi bàn giao. Nếu chọn chạy script
`Start-NexusDemo.ps1` thay cho các terminal trên, script sẽ giữ các port trong
bảng; không chạy thêm một bản service từ IDE cùng lúc. Dừng bản do script tạo
bằng `Stop-NexusDemo.ps1` trước khi chuyển sang IDE.

| Đăng nhập | Mật khẩu demo | Vai trò |
|---|---|---|
| `buyer@nexus.local` | `Nexus#Buyer2026` | Duyệt hàng, sửa profile, thêm giỏ |
| `seller@nexus.local` | `Nexus#Seller2026` | Thử API quản lý sản phẩm |
| `admin@nexus.local` | `Nexus#Admin2026` | Thử API quản lý user/danh mục |

Đây là tài khoản mẫu đã được khai báo trong `infra/staging/.env.example`; seed dùng lại BCrypt hash từ migration V3. Không dùng các mật khẩu này cho môi trường thật.

## Dữ liệu

| Database demo | Nội dung seed bổ sung |
|---|---|
| `nexus_demo_user_db` | 3 tài khoản với profile đủ tên, giới tính, địa chỉ, ngày sinh và role/quyền |
| `nexus_demo_catalog_db` | 10 nhóm theo thiết kế và 16 danh mục cũ; 22 sản phẩm gồm 18 ACTIVE, 2 DRAFT, 2 INACTIVE; 41 SKU; 44 ảnh |
| `nexus_demo_commerce_db` | Giỏ buyer có iPhone Black 128GB ×1 và áo cotton White M ×2 |

Migration catalog V3 còn có 5 sản phẩm ACTIVE sẵn, nên trang `/search` hiện có **23 sản phẩm ACTIVE**, đủ thử phân trang 12 sản phẩm/trang. Mọi sản phẩm ACTIVE trong bộ seed đều có SKU giá dương và ảnh. SKU và ảnh dùng UUID cố định bắt đầu `6d260021-`; các bản ghi migration giữ nguyên UUID riêng.

Đây là các database PostgreSQL thật trong container `nexus-postgres`, được giữ
trên Docker volume. File local `infra/local-dev/.env` hiện có:

```dotenv
USER_DB_NAME=nexus_demo_user_db
CATALOG_DB_NAME=nexus_demo_catalog_db
COMMERCE_DB_NAME=nexus_demo_commerce_db
```

Ba cấu hình datasource đọc các tên này khi chạy Maven/IDE bình thường. Trong
công cụ quản lý database, kết nối host `localhost`, port theo `DB_PORT` hiện là
5433, username/password từ file `.env`, rồi chọn một trong ba database trên.
Các DB `user_db`, `catalog_db`, `commerce_db` cũ vẫn được giữ. Catalog cũ thiếu
migration V3 dù đã có V4; bộ dữ liệu mới chạy đầy đủ V1–V4 trên database riêng,
không sửa lịch sử Flyway của DB cũ.

Seed giữ số lượng giỏ đã thay đổi khi demo, không nhân bản cùng SKU trong cùng
giỏ. Không xóa database hoặc dữ liệu người dùng tự thêm. SQL có transaction và
kiểm tra tên database trước khi ghi. Có thể chạy lại seed bằng script Start
khi đã dừng các bản service chạy thủ công.

## Kịch bản trình diễn

1. Đăng nhập buyer; mở tên tài khoản trên header để vào `/profile`, sửa tên/địa chỉ và Save Changes. Điện thoại hiện chỉ đọc theo contract BE.
2. Vào trang chủ để xem dải 10 icon đúng thiết kế. Bấm **Phones** để xem 3 điện thoại hoặc vào `/search` để xem 23 sản phẩm và thử phân trang.
3. Mở [iPhone demo](http://localhost:3000/products/demo-iphone-15-128gb?id=6d260021-0000-4000-8000-000000000201), đổi SKU, chọn quantity và Add to cart. FE phải gọi `POST /api/v1/carts/me/items` qua cổng 3000 → gateway 8080 và hiện `Added to cart.` sau response 201.
4. Có thể đăng ký tài khoản mới ở `/sign-up`; email/điện thoại/identityNumber phải khác tài khoản đã có.
5. Seller/Admin dùng smoke script để thử các API đã có BE nhưng chưa có màn hình FE.

Token được lưu trong `localStorage` bằng key `nexus.accessToken`; reload khôi phục phiên và đọc profile từ BE. Đăng xuất hoặc response 401 của phiên hiện tại xóa token. Chưa có trang xem giỏ riêng; kiểm tra cart bằng API hoặc DB. Ảnh minh họa là URL công khai và cần Internet.

Cốc gốm cũng đã có hai SKU để thêm vào giỏ. Schema catalog chưa có số lượng tồn kho. Tìm kiếm `q` và sắp xếp `sort` hiện được FE gửi nhưng BE chưa xử lý; dữ liệu demo không bổ sung các chức năng đó. Reputation, đổi mật khẩu và penalty trên profile vẫn là các phần chờ API.

## Kiểm tra và dừng

```powershell
node .\be-project-nexus\infra\demo\Test-NexusDemo.mjs
.\be-project-nexus\infra\demo\Stop-NexusDemo.ps1
```

Smoke test dùng API thật, kiểm tra các API TRUE trong workbook và cart; dữ liệu tạm chỉ được tạo trên database demo. Có thể truyền mật khẩu qua biến `DEMO_*_PASSWORD` để thay giá trị mẫu. Kết quả HTTP không lưu token/mật khẩu.

Lần xác minh API ngày 21/10/2026: **19/19 API TRUE, 33/33 checks, 107 assertions
pass**. Xem [bảng đối chiếu từng API](../../../fe-project-nexus/docs/api-integration-audit.md).
Trong 19 API TRUE có 7 được FE sử dụng trực tiếp; 9 endpoint thuộc quản trị/Seller,
2 endpoint đã có luồng tương đương bằng `/users/me` và category tree,
1 JWKS thuộc hạ tầng. Cart là lời gọi FE thứ 8 nhưng workbook còn đánh dấu FALSE.
Báo cáo verification chi tiết lưu local, không đưa vào Git.

Từ thư mục FE, `npm run test:profile` hiện kiểm tra browser với BE/FE đang chạy:
login, profile GET/PUT, catalog detail và cart được đọc/ghi thật. Test không
khởi động server giả hoặc tự thay response HTTP.
Lượt chạy sau thay đổi localStorage: **5/5 browser tests pass** trên `http://localhost:3000`. Sau sửa ảnh/menu, unit/contract **45/45 pass**, TypeScript pass, 29 focused BE tests pass; browser xác nhận 12/12 ảnh trang đầu và Seiko tải được. Server Next dev hiện tại gặp lỗi HMR khi dùng `127.0.0.1`; dùng hostname `localhost` để kiểm tra.

Menu header và dải icon được cố định theo thiết kế; dữ liệu sản phẩm, giá và ảnh
vẫn lấy từ BE. Script `seed-catalog-images.sql` bổ sung ảnh cho 5 sản phẩm có sẵn
từ migration nếu chúng chưa có ảnh, giữ nguyên mọi ảnh đã tồn tại.

Log, kết quả smoke và PID do script quản lý nằm ở `%LOCALAPPDATA%\NexusDemo`. Stop chỉ dừng cây process đã được script tạo, giữ lại database và Docker. Config/Discovery/Gateway đã chạy trước Start được tái sử dụng và không bị Stop dừng.

Lệnh tùy chọn `Start-NexusDemo.ps1 -SkipFrontend` chỉ chạy BE và seed. Nếu cổng
business service/FE đang do tiến trình khác chiếm, script yêu cầu dừng tiến trình
đó trước để tránh chạy nhầm database.
