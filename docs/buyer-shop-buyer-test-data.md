# Buyer demo — dữ liệu để kiểm tra cart/orders

Tài khoản được gắn với dữ liệu: `buyer@nexus.local`, UUID
`68000000-0000-4000-8000-000000000003`. Tài khoản profile trong `user_db`
đang ACTIVE. Password được quản lý bởi Keycloak, không bởi seed Commerce này.

## Dữ liệu đã chuẩn bị

Giỏ ACTIVE có ba SKU thật, mỗi SKU quantity=1, ảnh catalog public và snapshot
giá lấy từ catalog tại thời điểm seed:

| Sản phẩm | SKU | Giá snapshot |
| --- | --- | --- |
| Apple iPhone 15 128GB | DEMO-SHOP-1-1 | 15.990.000 VND |
| Everyday Cotton T-Shirt | DEMO-SHOP-9-1 | 199.000 VND |
| Classic White Sneakers | DEMO-SHOP-17-1 | 699.000 VND |

Tổng ban đầu: **16.888.000 VND**, ba item/ba đơn vị. Các sản phẩm hiện thuộc
cùng tài khoản seller demo, nên giỏ thể hiện một nhóm seller; không tạo danh tính
seller giả để làm nhiều nhóm. Giỏ EXPIRED cũ với chín dòng được giữ nguyên.

Năm đơn mẫu `NX-DEMO-SHOP-0001` đến `0005` giữ các trạng thái PENDING, PAID,
SHIPPED, COMPLETED, CANCELED để kiểm tra danh sách/detail/history. Trạng thái mẫu
không phải bằng chứng giao dịch SePay đã được xử lý.

## Chạy lại

Từ thư mục repo:

```powershell
& ./be-project-nexus/infra/local-dev/seed-buyer-shop.ps1 -Domains @('commerce')
& ./be-project-nexus/infra/local-dev/seed-buyer-shop.ps1 -VerifyOnly
```

Seed Commerce yêu cầu catalog demo và buyer/seller profile chuẩn đã tồn tại.
Nó đọc ba SKU ACTIVE có ảnh từ catalog, lấy giá hiện tại rồi nạp snapshot vào
Commerce, không tạo foreign key hoặc truy vấn join giữa hai database.

- Không sửa password, roles, Keycloak clients hay credentials.
- Không xóa/khôi phục giỏ lịch sử hoặc đổi trạng thái đơn đã thao tác.
- Giỏ ACTIVE có item sẽ được giữ nguyên, kể cả quantity hoặc snapshot buyer đã đổi.
- Nếu không có giỏ ACTIVE, tạo giỏ mới bằng UUID mặc định của DB; một giỏ seed cũ
  hết hạn không thể chặn lần seed tiếp theo do trùng ID.
- Nếu giỏ ACTIVE rỗng, seed ba SKU mẫu. Muốn giữ trạng thái rỗng để chụp evidence,
  không chạy seed lại sau khi xóa hết item.

## Kiểm tra đã chạy

- PowerShell syntax và `git diff --check` pass.
- Seed Commerce chạy hai lần; snapshot buyer cart/items/orders sau lần thứ hai
  giống hoàn toàn trước rerun, không thêm dòng hoặc ghi đè dữ liệu.
- Full VerifyOnly: 24 products, 72 SKUs, 24 productsWithImages, 72 balances,
  5 demo orders, 4 auctions.
- Transaction kiểm tra rồi ROLLBACK: quantity buyer sửa vẫn được giữ khi seed;
  giả lập giỏ cũ/fixed-ID đã EXPIRED vẫn tạo đúng một giỏ ACTIVE ba dòng, không
  thay chín dòng lịch sử. Dữ liệu giả lập không được commit.

Đăng nhập cart vẫn phụ thuộc việc đồng bộ password/client trong Keycloak và
`GET /api/v1/users/me` hoạt động; dữ liệu giỏ trong DB không tự sửa lỗi xác thực.

## Xác nhận đăng nhập live — 11/11/2026

Theo yêu cầu đồng bộ tài khoản của người dùng, mật khẩu của đúng buyer UUID003
đã được cập nhật trong Keycloak và biến DEMO_BUYER_PASSWORD trong `.env` local
gitignored. Không ghi mật khẩu vào tài liệu/Git, không đổi credential admin/seller.
Buyer trước đó bị khóa tạm sau năm lần sai; chỉ trạng thái khóa của buyer demo này
được xóa, không đổi chính sách brute-force của realm.

Client confidential `user-service` đã được cấu hình bằng secret BE hiện có,
service-account enabled, browser/implicit/password grants disabled. Service account
chỉ được thêm `realm-management/view-users` để đọc profile/role mapping cho luồng
đăng nhập này; không thêm manage-users/query-users/view-realm. Token client credentials
và đọc role mapping buyer trả thành công. Việc này không chứng minh registration
hay cập nhật tài khoản qua Admin API đã có đủ quyền.

Đã nhập tài khoản buyer và mật khẩu người dùng cung cấp trên Keycloak bằng luồng
browser Authorization Code + PKCE thật. FE xử lý callback và điều hướng tới `/cart`,
header hiển thị **Hi Nexus Buyer**, cart có **3 items / 1 seller**, ba ảnh được render,
tổng **16.888.000 ₫** và các nút quantity/remove. Không dùng auth/cart mock hoặc token
giả để kiểm tra. Đây là bằng chứng đăng nhập/profile/cart live mới, thay thế giới hạn
profile503 của lần kiểm tra trước; shipping/SePay settlement vẫn là phụ thuộc riêng.
