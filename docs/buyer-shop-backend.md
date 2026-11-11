# Buyer Shop: API, Supabase và dữ liệu demo

Đối chiếu source trên `origin/develop`, ngày 10/11/2026. Đây là báo cáo source và kiểm tra local; không thay cho trạng thái phát hành của các nhóm.

## API thực tế trong code

| Giao diện | API đã có | Giới hạn thực tế |
| --- | --- | --- |
| Danh mục/home/list | `GET /api/v1/categories/tree`, `/categories/{id}`, `/products` | Product list chỉ nhận `categoryId,page,size`. Chưa có full-text search, price/brand/rating filter hoặc sort. |
| Chi tiết sản phẩm | `GET /api/v1/products/{id}`, `/products/{id}/skus`, `/skus/{id}` | Có description, variants, ảnh và giá SKU; chưa có API đánh giá sản phẩm, specifications có cấu trúc, wishlist hoặc seller storefront. |
| Ảnh Supabase | `POST /api/v1/products/{id}/images`, `DELETE .../{imageId}`, `PUT .../order` | Seller sở hữu sản phẩm + `PRODUCT.UPDATE`; multipart field `file`; JPEG/PNG/WebP, tối đa 5 MB và 5 ảnh theo cấu hình. |
| Đấu giá | `GET /api/v1/auctions?status=ACTIVE` hoặc `SCHEDULED`, `GET /api/v1/auctions/{id}` | Có list/detail, create/schedule. Đọc controller để xác nhận tính năng bidding riêng. |
| Giỏ hàng | `/api/v1/carts/me`, `/me/items`, `/me/items/{id}`, `/me/pricing`, `/me/validate`, `/guest`, `/me/merge` | Dữ liệu riêng cần Keycloak JWT; item quantity/update/remove đã có controller. |
| Checkout | `POST /api/v1/checkout/preview`, `POST /api/v1/orders` | Shipping preview có thể trả `shippingFee=null`. Chưa có controller provinces/districts/wards trong shipment-service dù gateway đã khai báo route/quyền. Không coi phí demo là phí thanh toán thật. |
| Kết quả/đơn hàng | `GET /api/v1/orders`, `/orders/{id}`, `/orders/{id}/history`, `POST /orders/{id}/cancel` | OrderResponse chưa có shipping address, payment fields hoặc orderGroupId; FE phải dùng dữ liệu DTO thật và đánh dấu phần minh họa. |
| Thanh toán | `POST /api/v1/payments`, SePay webhook | Initiation/provider riêng; dữ liệu demo không gửi tiền và không mô phỏng webhook thành công. |
| Rating | rating-service có schema/consumer | Chưa có public controller để đọc reviews/rating cho Product Detail. |

Product list đã trả `images` trên develop qua `ProductService.attachImages` và một truy vấn batch `findImagesForProducts`. OpenAPI `Product.images` hiện là optional; không cần thêm N+1 detail requests để lấy ảnh card.

## Cấu hình Supabase

- Project: `https://nzjvjvyqxcisjgvglwvj.supabase.co`.
- Bucket public: `product-images`.
- BE dùng `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_BUCKET` trong `infra/local-dev/.env` (gitignored).
- `SUPABASE_PUBLISHABLE_KEY` chỉ là client key public, không thay thế service key phía BE. Đọc ảnh public không cần chuyển key/token lên URL.
- FE gửi multipart tới BE; BE kiểm tra owner, giới hạn số ảnh, MIME thực, kích thước, upload object rồi lưu URL vào catalog DB. Không đưa service key lên trình duyệt.

Upload một ảnh và kiểm tra public bytes bằng PowerShell 7+:

```powershell
$env:NEXUS_SELLER_ACCESS_TOKEN = '<seller access token from OIDC session>'
.\infra\local-dev\upload-product-image.ps1 -ProductId '<owned product uuid>' -ImagePath '.\image.jpg'
```

Không ghi access token/service key trong commit, lệnh mẫu hoặc báo cáo. Script mặc định đi qua gateway `http://localhost:8080`; khi kiểm tra trực tiếp catalog dùng `-ApiBaseUrl http://localhost:8082`.

## Dữ liệu demo local có thể chạy lại

Sau khi migrations của các service đã chạy và tài khoản demo chuẩn đã tồn tại:

```powershell
pwsh -File .\infra\local-dev\seed-buyer-shop.ps1
pwsh -File .\infra\local-dev\provision-buyer-shop-images.ps1
pwsh -File .\infra\local-dev\seed-buyer-shop.ps1
```

Lần seed cuối bỏ các fallback ảnh dùng chung khi sản phẩm đã có object riêng được upload qua BE. Script SQL chỉ chạy khi gọi thủ công; không phải Flyway production migration.

Dataset namespace `92000000` gồm 24 sản phẩm active, 72 SKU (3 variant/sản phẩm), 2 category con, 24 product stats, 72 balance tại kho `DEMO-SHOP-HCM` với ledger intake tương ứng, 2 active + 2 scheduled auctions và 5 order với trạng thái PENDING/PAID/SHIPPED/COMPLETED/CANCELED, line snapshot và history. Active auctions có startAt trong quá khứ/endAt tương lai, currentPrice bằng startingPrice và bidCount=0; không tạo bid giả. Seed chỉ nâng cấp 2 scheduled fixture cũ nếu chưa chỉnh sửa, chưa có bid, version=0. Cart của `buyer@nexus.local` chỉ được thêm 3 dòng nếu đang trống; giỏ đã có dữ liệu được giữ nguyên. Seller UUID `68000000-0000-4000-8000-000000000002`, buyer UUID kết thúc `000003`.

Các ảnh fixture điện thoại/áo/giày được tái sử dụng cho demo; chưa phải bộ ảnh sản phẩm chính thức tương ứng từng model. Re-run không tăng tồn kho đã bán, không đổi trạng thái order đã thao tác và không thay tài khoản khác. Transaction riêng từng service DB; không tạo FK liên DB hoặc gửi event/provider payment khi seed.

## Bằng chứng đã chạy

- Khởi động lại container `nexus-postgres` sẵn có, không xóa volume. Flyway validate checksum và migrate catalog từ V6 lên V7, commerce V6 lên V8, auction V2 lên V4; tạo inventory_db và migrate V1–V7.
- Seed chạy lặp nhiều lần: vẫn 24 products, 72 SKUs, 72 balances, 5 orders, 4 auctions. Kiểm tra 72 balance tuân thủ công thức tồn kho, tổng intake 7200; 5 order totals khớp line totals. 11 order cũ vẫn còn.
- Catalog build từ source develop, chạy local port 8082 và thực tế `GET /api/v1/products?size=100` trả 37 sản phẩm (24 demo mới + 13 dòng có trước).
- Đăng nhập seller qua Keycloak OIDC Authorization Code + PKCE, không bật password grant. Upload 24 ảnh qua BE với HTTP201; mỗi public object HTTP200 và SHA256 bằng bytes fixture. Reorder qua BE; sau seed cleanup mỗi demo product có 1 ảnh owned.
- `ProductImageUploadTest`: 18/18 pass. `ProductImagePersistenceTest`: 21/21 pass khi đặt timezone JVM `Asia/Ho_Chi_Minh`. Lần đầu lỗi môi trường vì PostgreSQL 18 không nhận alias hệ điều hành `Asia/Saigon`; không thay assertion hay logic ứng dụng.
- `mvn -o package -DskipTests` catalog thành công. Lần verify sau test bị Windows khóa JAR do service đang chạy; đóng process owned rồi `mvn -o verify -DskipTests` thành công, 39 kết quả test ở bước trước vẫn được giữ. Runtime sau đó dùng bản copy JAR để Maven không bị khóa.
- Inventory trên develop khai báo trùng `common-security`, khiến Maven không đọc được POM. Bỏ declaration dư, giữ bản `${project.parent.version}`; `mvn -o verify` thành công với 23 tests. Không đổi behavior inventory.
- Build current source gateway, commerce, auction thành công. Gateway local port 8080 dùng SimpleDiscovery URI bằng tham số process, không sửa config production. Public products trả 37 rows, active auctions trả 2 và scheduled auctions trả 2. Buyer OIDC PKCE qua gateway đọc được 5 demo orders, detail PENDING, history, cart có 9 dòng sẵn có; checkout preview với request hợp lệ trả `shippingFee=null` đúng giới hạn đã nêu.
- Script batch checked-in chạy lại qua catalog trả `uploaded=0, verified=24`. Ở lượt cũ, snapshot shipping_info của 5 demo orders khớp `CreateOrderRequest.snapshot()`. Cleanup theo review sau đó bỏ `district` khỏi receipt seed và chỉ nâng cấp các snapshot mặc định chưa chỉnh sửa; contract tạo đơn thật của Commerce vẫn giữ yêu cầu district cho đến đợt tích hợp checkout riêng.
- Postman CLI trên máy không hỗ trợ `postman status` (unknown command); không suy diễn là đã có Postman workspace/simulation evidence. Orbit tìm được management Storage API nhưng không có endpoint upload phù hợp; kiểm chứng upload dựa trên BE source và lần gọi live ở trên.

## Runtime để review và phần chưa chạy

Đang giữ PostgreSQL, Redis, Keycloak, gateway `8080`, user `8081`, catalog `8082`, commerce `8083`, auction `8084`, inventory `8085` để review. Business services tắt Kafka listener/outbox relay; auction scheduler và cart expiry tắt trong process review để không tự đổi dữ liệu cũ. Commerce vẫn dùng inventory stub theo config develop, vì vậy việc đọc dữ liệu local không chứng minh reservation end-to-end.

Sau khi người dùng thêm `KEYCLOAK_USER_SERVICE_CLIENT_SECRET`, user-service đã khởi động ở port 8081, dùng clone `buyer_shop_user_db` từ user_db được migrate lên V7; user_db gốc vẫn ở V6. V7 bỏ các bảng/cột auth cũ nên không tự áp dụng vào DB gốc có credentials. Kiểm tra read-only Keycloak admin vẫn trả 0 client `user-service`; buyer đăng nhập OIDC PKCE thành công nhưng `GET /api/v1/users/me` qua gateway trả HTTP503 vì thiếu confidential client để đọc roles. Cần hoàn tất tạo client qua script cấu hình Keycloak rồi kiểm tra profile lại; chỉ thêm secret trong .env chưa hoàn tất bước này.

Automatic approval review đã chặn thao tác tạo confidential client user-service và gán các role theo `keycloak/seed-users.sh`; tool chỉ trả `blocked by policy`, không có lý do chi tiết hơn. Không thử lại mutation đó. Người dùng cần cấu hình client và secret bằng quy trình Keycloak sẵn có trước khi profile/registration live hoạt động.

**Cập nhật11/11/2026:** Sau khi người dùng yêu cầu đồng bộ đúng buyer để test, đã
đồng bộ mật khẩu duy nhất buyer với `.env` gitignored, gỡ khóa buyer và cấu hình
client user-service chỉ với quyền đọc view-users. Không chạy lại thao tác grant bộ
quyền quản trị đầy đủ từng bị từ chối. Đăng nhập PKCE trên browser thật đã vào cart,
profile/header buyer và ba item có ảnh được tải thành công. Lỗi profile503 ở trên
là lịch sử trước cấu hình này; không còn chặn luồng đọc buyer/cart đã kiểm tra.
Registration/profile-write và payment/shipping chưa được claim hoàn chỉnh bởi thay
đổi quyền đọc này. Chi tiết ở [buyer test data](buyer-shop-buyer-test-data.md).

Payment, shipment, rating, notification service chưa được khởi động trong phiên này. Không claim có giao dịch checkout/payment live. Log runtime/test/build nằm trong `*/target/buyer-shop*.log` (gitignored).
