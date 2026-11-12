# Đối chiếu contract shipping #201

Bảng này đối chiếu các chi tiết nhìn thấy trong năm ảnh contract người dùng cung cấp với code hiện tại. Các ví dụ trong ảnh chứng minh payload được minh họa; riêng một giá trị ví dụ không chứng minh toàn bộ enum hoặc các ràng buộc chưa thể hiện. Theo phạm vi người dùng chốt ngày 10/11/2026, #201 giao shipping contract; việc sửa checkout BE và nguồn dữ liệu tích hợp sẽ thuộc issue riêng do người dùng tạo.

| Hạng mục | Thông tin trong ảnh | Code hiện tại | Phân loại |
|---|---|---|---|
| Dòng địa chỉ public | `toAddress.line1` | Đã đổi public `ShippingAddressRequest` sang `line1`; adapter/pickup nội bộ vẫn dùng `addressLine` | Lệch tên field đã sửa |
| Carrier metadata | Có `code`, `name`, `isDefault`, `available` | Đã bổ sung đủ bốn field; default lấy từ registry, available lấy từ breaker | Field bị thiếu đã sửa |
| Cấp địa giới | Ví dụ dùng mã hệ cũ như tỉnh `202`, phường `20308`; wards lọc qua district | Đã chuyển sang danh mục GHN v3 hai cấp theo yêu cầu sau đó của người dùng; wards nhận `provinceCode` | Khác biệt có chủ đích do đổi mô hình địa chỉ |
| Mã địa giới | Mã ví dụ thuộc contract cũ | Dùng `_id` v3 dạng chuỗi số nguyên dương chuẩn; phường thuộc trực tiếp tỉnh, chỉ giữ bản ghi đang hoạt động | Không trộn hai hệ mã |
| Các trường quote cấp gốc | Ảnh có `toAddress`, `items`, `warehouseId`; ví dụ `warehouseId: null` | API shipping yêu cầu `parcel`; `insuranceValue` là tùy chọn | Nguồn parcel trong checkout thuộc issue checkout BE riêng |
| Kho mặc định | `warehouseId: null` trong ví dụ | DTO chấp nhận null; OpenAPI đánh dấu nullable; null hoặc bỏ trống dùng UUID kho mặc định đã cấu hình | Đã đồng bộ nullable với code |
| Các trường địa chỉ nhận | Ảnh có `line1`, `wardCode`, `districtCode`, `provinceCode`; không có tên/số điện thoại người nhận | API shipping bỏ district theo v3, giữ line1 và nhận `recipientName`, `recipientPhone` để preview GHN | Mapping recipient của checkout thuộc issue checkout BE riêng |
| Loại dịch vụ | Ví dụ phản hồi dùng `STANDARD`; người dùng sau đó đã chốt `STANDARD`/`HEAVY` theo cân nặng | Public enum `STANDARD` cho kiện dưới 20.000g, `HEAVY` từ 20.000g; GHN 2/5 chỉ dùng nội bộ; fake dùng cùng ngưỡng | Mapping đã được quyết định và đồng bộ |
| Khai bảo hiểm | Không xuất hiện trong request minh họa | `insuranceValue` tùy chọn, mặc định 0, VND nguyên từ 0 đến 5.000.000 | Không phải trường bắt buộc của GHN; không được dùng để biện minh request bắt buộc mới |

## Những phần đã sửa

Public quote nhận `toAddress.line1`; không yêu cầu client dùng tên nội bộ `addressLine`. Carrier response có đủ `isDefault` và `available`. Hai thay đổi này sửa chỗ code lệch với field đã thể hiện trong ảnh và không đòi hỏi người dùng đổi contract để theo tên tự đặt trong implementation.

Người dùng đã quyết định enum public `serviceType`: GHN loại 2 ánh xạ `STANDARD` cho kiện dưới 20.000g; GHN loại 5 ánh xạ `HEAVY` cho kiện từ 20.000g. Fake dùng cùng ngưỡng. Đây là quyết định rõ ràng sau bước đối chiếu ảnh; không còn để client nhận ID provider dạng chuỗi `2`/`5`. Việc chuyển enum không thay đổi mô hình địa chỉ hoặc yêu cầu dữ liệu người nhận/parcel.

`isDefault` nghĩa là adapter mặc định của registry. `available` phản ánh khả năng được thử gọi theo trạng thái circuit breaker: false khi OPEN/FORCED_OPEN, true khi CLOSED/HALF_OPEN hoặc chưa có breaker. Việc đọc danh sách carrier không gọi mạng tới GHN. HALF_OPEN cho phép probe theo cấu hình; giá trị available không phải kết quả kiểm tra sức khỏe trực tiếp và không giữ chỗ probe.

## Khác biệt do chuyển sang GHN v3

Người dùng đã yêu cầu danh mục mới sau sáp nhập. Vì vậy bỏ cấp district và dùng `GET /api/v1/shipping/wards?provinceCode=...` là thay đổi có chủ đích so với ví dụ ba cấp. `provinceCode` và `wardCode` phải lấy từ `_id` của danh mục v3; không chuyển mã v3 vào `district_id` hoặc ghép với mã cũ như `202`/`20308`. Tên chuẩn địa giới được resolve từ danh mục mới khi gửi preview với cờ địa chỉ mới.

## Dependency checkout đã được chuyển phạm vi

Ảnh request gốc không có parcel hoặc thông tin người nhận. API shipping #201 hiện yêu cầu `parcel`, `recipientName`, `recipientPhone`; request tối thiểu trong ảnh chưa đủ để gọi API này. Người dùng đã chọn tách việc sửa checkout BE sang issue riêng và đã gửi yêu cầu FE #150 chuyển sang hai cấp. Dependency tích hợp được bàn giao rõ trong [tài liệu FE handoff](shipping-address-v3-fe-handoff-201.md), không dùng nó để yêu cầu #201 triển khai thêm commerce checkout.

Issue checkout BE riêng sẽ xử lý nguồn số đo kiện, recipient, mapping preview/order hai cấp và phí/total tin cậy cùng #150/#151. Không tự dùng số đo hoặc số điện thoại giả để giảm trường bắt buộc. `insuranceValue` giữ tùy chọn; không tự thay bằng toàn bộ subtotal. Việc chưa triển khai các thay đổi checkout không chặn nghiệm thu riêng adapter/API shipping.

Tài liệu này không ghi nhận một payload tạo đơn hoặc booking mới. Quote tiếp tục chỉ gọi preview; các quyết định create/tracking thuộc scope riêng.
