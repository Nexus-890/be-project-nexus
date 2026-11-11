# Buyer Shop — dữ liệu DB và cách khởi động local

Dữ liệu Shop được lưu trong PostgreSQL của BE, không phải bản ghi tạm trong trình duyệt. Ở lần kiểm tra ngày 10/11/2026, DB vẫn có 37 sản phẩm, trong đó 24 sản phẩm demo mới, 72 SKU, 24 ảnh owned trên Supabase, 72 stock balances, 5 đơn demo và 4 auction. Catalog gọi trực tiếp ở port 8082 trả dữ liệu thành công. Trang web lỗi tải dữ liệu trong khi đó vì FE trỏ gateway port 8080 nhưng gateway không lắng nghe.

## Chạy bằng luồng local có seed

Từ thư mục `be-project-nexus`, dùng entry point local:

```powershell
pwsh -File infra/local-dev/start-buyer-shop.ps1
```

Startup cần khởi động gateway/catalog/auction bằng cấu hình local đã lưu và chờ migrations trước khi gọi seed. Nó gọi script seed opt-in cho hai domain public; FE sau đó gọi gateway để đọc dữ liệu DB bình thường. Không cần đăng nhập hoặc upload lại ảnh để xem trang chủ, category và product detail public.

Các domain private có thể được seed khi migrations của user/commerce/inventory đã sẵn sàng. Root startup có tùy chọn `-IncludeCheckoutData` cho trường hợp này; việc có dữ liệu order/cart không thay thế các API shipping/payment còn thiếu.

Helper dùng lại các service IDE đã healthy và không dừng/chạy trùng chúng. `-Build`
chỉ build service cần khởi động, không rebuild/restart instance healthy. Gateway
actuator bị bảo vệ nên helper kiểm tra route catalog public và các route thực tế.

## Chạy hoặc kiểm tra seed riêng

Từ PowerShell tại `be-project-nexus`:

```powershell
# Chỉ dữ liệu public, không cần user-service/commerce/inventory hoạt động.
& ./infra/local-dev/seed-buyer-shop.ps1 -Domains @('catalog','auction') -WaitSeconds 120

# Chỉ kiểm tra, không ghi DB.
& ./infra/local-dev/seed-buyer-shop.ps1 -Domains @('catalog','auction') -VerifyOnly

# Dataset đầy đủ, sau khi migrations/user demo chuẩn đã tồn tại.
& ./infra/local-dev/seed-buyer-shop.ps1
& ./infra/local-dev/seed-buyer-shop.ps1 -VerifyOnly
```

`-Domains` nhận `catalog`, `inventory`, `commerce`, `auction`; mặc định là cả bốn. Script chạy theo đúng thứ tự dependency, kiểm tra schemas/prerequisites trước lần ghi đầu tiên và dùng transaction riêng cho mỗi service DB. `-WaitSeconds` chờ các schema được migrate, không tự sửa schema. Nếu chọn auction/inventory/commerce riêng, catalog demo cần được seed trước. Commerce kiểm tra các tài khoản buyer/seller chuẩn có sẵn; script không tạo hoặc thay credentials.

`-VerifyOnly` trả số lượng dataset mà không chạy SQL seed. Public verification phải có 24 products, 72 SKUs, 24 productsWithImages và 4 auctions. Full verification còn có 72 stockBalances và 5 orders.

## Dữ liệu tồn tại sau restart

Compose gắn named volume `nexus-local_postgres-data` vào `/var/lib/postgresql` của container `nexus-postgres`. Dừng/khởi động lại container hoặc service không xóa dữ liệu. Chỉ cần seed lại khi dùng DB/volume khác hoặc bổ sung dataset; script có thể chạy lại trong startup mà không tạo bản ghi trùng.

Seed nằm trong `infra/local-dev/buyer-shop/*.sql`, dùng namespace UUID `92000000` và được gọi rõ ràng trong luồng local. Đây không phải migration tự động cho production. Các migration Flyway đã áp dụng không bị chỉnh sửa. Nếu xóa named volume, PostgreSQL sẽ là DB mới; cần khởi động các service để migrate rồi chạy startup/seed local lại.

Các SQL dùng `ON CONFLICT DO NOTHING`, giữ giá/trạng thái sản phẩm đã chỉnh, không nạp lại tồn kho đã sử dụng và không thay đổi trạng thái order đã thao tác. Giỏ buyer chỉ được thêm dòng khi giỏ hiện đang rỗng. Các auction đã kết thúc/hủy/có bid không tự mở lại; do đó một DB dùng nhiều ngày có thể hiển thị trạng thái không có auction ACTIVE, nhưng API vẫn phải trả thành công.

Snapshot địa chỉ của đơn demo không còn lưu `district`. Chạy lại seed chỉ nâng
cấp đúng hai dạng snapshot mặc định từ các phiên bản seed cũ; địa chỉ đã được
người dùng sửa được giữ nguyên. Đây là cleanup dữ liệu receipt mẫu, không thay
contract checkout: Commerce hiện vẫn yêu cầu `shippingAddress.district` khi tạo
đơn thật; chuyển checkout sang tỉnh/phường hai cấp thuộc tích hợp riêng.

## Ảnh sản phẩm

Seed mới sử dụng các URL Supabase public đã có trong dataset catalog. DB đã provision ảnh owned giữ nguyên URL riêng của từng sản phẩm. Startup/seed không gửi yêu cầu upload và không cần Supabase service key trong FE. Chỉ workflow upload seller qua BE cần cấu hình quyền/key phù hợp.

Ảnh demo tái sử dụng phone/shirt/sneaker fixtures, chưa phải bộ ảnh chính thức riêng cho từng model. Lần kiểm tra đọc lại hiện tại xác nhận 24 public image URLs HTTP200 và SHA256 khớp fixture, không gửi Authorization lên URL ảnh.

## Phân biệt lỗi kết nối và DB rỗng

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/products?page=0&size=10'
Invoke-RestMethod 'http://localhost:8080/api/v1/categories/tree'
Invoke-RestMethod 'http://localhost:8080/api/v1/auctions?status=ACTIVE&page=0&size=5'
```

Response thành công có envelope `{code,message,data}` và page metadata khi endpoint trả list. Nếu gateway không kết nối được nhưng catalog/auction gọi trực tiếp có dữ liệu, sửa startup/routing trước; seed thêm dữ liệu không sửa được lỗi mất gateway. Empty `data: []` với HTTP200 là trạng thái khác với lỗi request mà UI đang báo `Unable to load`.

## Kiểm tra đã chạy cho thay đổi seed

- PowerShell parser kiểm tra syntax script thành công.
- `-Domains @('catalog','auction') -VerifyOnly` trả đủ dataset public.
- Dataset đầy đủ được seed lặp lại và `-VerifyOnly` xác nhận số lượng giữ nguyên.
- Volume PostgreSQL được kiểm tra đang là named volume persisted.
- Public images được đọc lại bằng HTTP và đối chiếu SHA256, không tạo upload hoặc thay đổi Storage.

Không claim dữ liệu demo khiến shipping quote/payment settlement/reservation thật hoàn chỉnh; các dependency đó được ghi trong [báo cáo BE](buyer-shop-backend.md).
