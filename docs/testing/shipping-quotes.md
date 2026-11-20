# Kiểm chứng báo giá vận chuyển #201

## Contract địa chỉ GHN v3 sau sáp nhập

Shipment dùng mô hình hai cấp: tỉnh/thành phố → phường/xã. Bốn API thuộc `shipment-service` và được gateway chuyển tiếp tại `/api/v1/shipping/**`, `/api/v1/carriers/**`:

| API | Quyền | Kết quả |
|---|---|---|
| `GET /api/v1/carriers` | JWT và `SHIPPING.QUOTE` | Carrier đã đăng ký |
| `GET /api/v1/shipping/provinces` | Public | Tỉnh/thành phố đang hoạt động trong danh mục GHN v3 |
| `GET /api/v1/shipping/wards?provinceCode=` | Public | Phường/xã đang hoạt động thuộc đúng tỉnh/thành phố |
| `POST /api/v1/shipping/quotes` | JWT và `SHIPPING.QUOTE` | Carrier/serviceType, phí và ETA |

Carrier response có `code`, `name`, `isDefault` và `available`. `isDefault` phản ánh adapter mặc định của registry. `available` đọc trạng thái circuit breaker, trả false khi OPEN/FORCED_OPEN và true khi CLOSED/HALF_OPEN hoặc chưa có breaker; không gọi probe GHN. Breaker tự chuyển từ OPEN sang HALF_OPEN sau thời gian cấu hình. Giá trị true không bảo đảm GHN đang hoạt động hoặc giữ chỗ cho lượt probe HALF_OPEN.

Contract OpenAPI phiên bản `2.1.0` bổ sung `insuranceValue` tùy chọn, bỏ trống hoặc null tương đương 0. Thay đổi này giữ tương thích với request 2.0. Contract từ 2.0 thay đổi không tương thích với draft ba cấp trước đó: bỏ operation `districts`; `wards` nhận `provinceCode`; bỏ `districtCode` khỏi địa chỉ quote và pickup; thêm `recipientName`, `recipientPhone` bắt buộc vào địa chỉ nhận. FE checkout và commerce cần chuyển contract đồng bộ trước khi dùng API mới. Thay đổi này chỉ sửa backend; chưa chuyển giao diện checkout đang giữ mô hình ba cấp.

`code` địa giới là trường `_id` của GHN v3 được biểu diễn bằng chuỗi số nguyên dương chuẩn, từ 1 đến 9 chữ số. Không dùng `ProvinceID`, `WardCode` của danh mục cũ và không gửi mã v3 vào trường district của API cũ. Chỉ trả địa giới `status=1`; tỉnh có `parent_id=1`, phường có `parent_id` trùng mã tỉnh được yêu cầu. GHN trả HTTP 200 với `data=null` khi `province_id` sai vẫn được đổi thành lỗi địa giới 400.

Mọi danh sách có `code`, `message`, `data` và `page` cùng cấp. `code` thành công bằng `0`, `message` bằng `response success`; trang logic có `number=0`, `size=max(1,count)`, `totalElements=count`, `totalPages=count > 0 ? 1 : 0`. Phí là số VND, ETA là timestamp ISO-8601 có múi giờ.

`serviceType` public đã được chốt thành enum `STANDARD` và `HEAVY`. Kiện có `weightGrams < 20000` trả `STANDARD`, ánh xạ nội bộ sang GHN `service_type_id=2`; kiện `weightGrams >= 20000` trả `HEAVY`, ánh xạ sang GHN `service_type_id=5`. Đúng 20.000g thuộc `HEAVY`. Fake dùng cùng ngưỡng; client không nhận ID GHN dạng chuỗi `2`/`5`.

Lỗi do shipment-service trả là `application/problem+json` với `SHIPMENT_*`. Lỗi JWT/quyền/rate limit hoặc kết nối do gateway phát ra trước khi tới service vẫn giữ mã chung của gateway. Public endpoint không cần JWT; một token được gửi kèm nhưng không hợp lệ vẫn có thể bị từ chối.

## Dữ liệu GHN v3 và preview

Adapter đọc hai danh mục mới:

- `GET /shiip/public-api/v3/master-data/province/all`.
- `GET /shiip/public-api/v3/master-data/ward/all-by-province-id?province_id=...`.

Quote gọi `POST /shiip/public-api/v2/shipping-order/preview` với `is_new_to_address=true` và `is_new_from_address=true`. Đây là endpoint preview hiện được GHN công bố ở path v2 nhưng hỗ trợ hệ địa chỉ mới. Tên chuẩn tỉnh/phường được resolve nguyên văn từ danh mục v3 để gửi trong `to_province_name`, `to_ward_name`, `from_province_name`, `from_ward_name`. Không tự dựng endpoint quote v3 hoặc dùng mã tỉnh/phường mới như mã quận cũ.

Preview là chạy thử, không tạo đơn. Adapter kiểm tra `order_code` rỗng, lấy phí từ `total_fee` và ETA từ `expected_delivery_time`. `GHN_PAYMENT_TYPE_ID` cấu hình bên trả phí, mặc định `1` là shop trả theo luồng thanh toán trước SEPAY; `GHN_REQUIRED_NOTE` cấu hình chính sách kiểm hàng, mặc định `KHONGCHOXEMHANG`. `cod_amount=0` vì luồng hiện tại không thu hộ. Các giá trị này có ý nghĩa nghiệp vụ của GHN và được đưa vào preview đúng cấu hình; quote không lưu chúng vào order hoặc tạo vận đơn.

Adapter không gửi `from_name` và `from_phone`; GHN dùng tên và số điện thoại người gửi từ hồ sơ của `ShopId` theo contract được công bố. Tên và số điện thoại người nhận vẫn bắt buộc lấy từ request. Địa chỉ pickup cụ thể cùng tên tỉnh/phường đã resolve luôn được gửi tường minh, không dùng dữ liệu quận/huyện cũ.

`items` dùng để tóm tắt số SKU và tổng số đơn vị trong nội dung hàng gửi. `parcel` mô tả một kiện đã đóng gói. Với dịch vụ cần `items[]` theo kiện, adapter gửi một phần tử kiện có `quantity=1` và số đo của parcel; `1` là số kiện, không thay thế quantity của các SKU và không nhân lại khối lượng kiện theo số lượng sản phẩm.

Người nhận phải có tên và số điện thoại thực tế. `recipientName` hiện bắt buộc trong code, tối đa 1024 ký tự. `recipientPhone` phải khớp `0[0-9]{9}|\+84[0-9]{9}`. Public request dùng `line1`, bắt buộc và tối đa 255 ký tự. Pickup nội bộ vẫn dùng `addressLine` cho địa chỉ cụ thể đã xác minh. Không dùng dữ liệu người nhận giả để vượt qua validation của GHN.

Ảnh contract gốc chỉ có `toAddress`, `items`, `warehouseId` và chưa có recipient/parcel. OpenAPI mô tả API shipping #201 nhận dữ liệu này; nguồn dữ liệu và mapping vào commerce preview/order sẽ được giải quyết trong issue checkout BE riêng do người dùng tạo. Người dùng đã gửi hướng dẫn chuyển hai cấp cho FE #150. [Bảng đối chiếu contract #201](../api/shipping-contract-review-201.md) ghi rõ khác biệt và phạm vi bàn giao. `insuranceValue` là tùy chọn, không phải trường bắt buộc của GHN.

## Chuẩn bị cấu hình

Tạo dữ liệu `.env` trên máy chạy backend; không commit file thật và không dán token vào YAML, body request, frontend hoặc log. `GHN_TOKEN` được shipment-service đọc trực tiếp từ environment hoặc `.env` đã import; YAML không chứa property cho token. Cần sandbox token/ShopId đang hoạt động và địa chỉ lấy hàng đã xác minh.

| Biến | Ý nghĩa |
|---|---|
| `SHIPPING_QUOTE_CARRIER=GHN` | Chọn GHN cho báo phí và danh mục địa chỉ |
| `SHIPPING_BOOKING_CARRIER=FAKE` hoặc `GHN` | Chọn FAKE để dùng phí GHN với mã giả; chọn GHN để dùng phí GHN với vận đơn sandbox; NONE tắt booking |
| `SHIPPING_CARRIER=GHN` | Cấu hình cũ, dùng cho cả hai khi không đặt override |
| `SHIPPING_CHECKOUT_CARRIERS=` | Danh sách hãng thêm cho người mua chọn ở checkout, ví dụ `FAKE`; để trống thì chỉ có quote carrier và booking theo `SHIPPING_BOOKING_CARRIER` |
| `SHIPPING_CARRIER_STRATEGY=CHEAPEST` | Rule chọn hãng đề xuất: `CHEAPEST` (phí rồi ngày giao) hoặc `FASTEST` (ngày giao rồi phí) |
| `GHN_BASE_URL=https://dev-online-gateway.ghn.vn` | Base URL sandbox |
| `GHN_TOKEN` | Token server của GHN |
| `GHN_SHOP_ID` | ShopId của tài khoản sandbox |
| `GHN_PAYMENT_TYPE_ID=1` | Bên trả phí GHN: `1` shop/người bán, `2` người nhận; mặc định shop trả trong luồng trả trước |
| `GHN_REQUIRED_NOTE=KHONGCHOXEMHANG` | Chính sách kiểm hàng: `KHONGCHOXEMHANG`, `CHOXEMHANGKHONGTHU` hoặc `CHOTHUHANG` |
| `GHN_HTTP_TIMEOUT_MS=1500` | Thời gian tối đa cho một HTTP attempt GHN, luôn bị giới hạn bởi phần deadline tổng còn lại |
| `GHN_MASTER_DATA_CACHE_TTL_MS=300000` | TTL cache danh mục hợp lệ trong shipment-service, mặc định 5 phút; đặt 0 để không lưu cache |
| `SHIPPING_API_TIMEOUT_MS=3000` | Ngân sách tổng cho một thao tác, gồm lookup và preview/retry |
| `SHIPPING_RETRY_LIMIT=3` | Số retry tối đa sau lần gọi đầu |
| `SHIPPING_RETRY_BACKOFF_MS=100` | Thời gian chờ ban đầu, tăng theo cấp số nhân trong deadline |
| `SHIPPING_BREAKER_FAILURE_THRESHOLD=5` | Ngưỡng thao tác thất bại trước khi tạm ngắt carrier |
| `SHIPPING_BREAKER_OPEN_MS=30000` | Thời gian mở breaker trước khi cho phép probe |
| `FREE_SHIPPING_THRESHOLD=1000000` | Subtotal đạt hoặc vượt ngưỡng thì phí khách trả bằng 0 |
| `SHIPPING_CATALOG_BASE_URL` | Mặc định để trống để OpenFeign tìm `catalog-service` qua Spring Cloud LoadBalancer/Eureka; có thể đặt URL trực tiếp khi kiểm tra local |
| `SHIPPING_DEFAULT_WAREHOUSE_ID` | UUID kho mặc định đã được provisioning xác minh |
| `SHIPPING_PICKUP_PROVINCE_CODE` | Mã `_id` tỉnh/thành phố của pickup trong danh mục GHN v3 |
| `SHIPPING_PICKUP_WARD_CODE` | Mã `_id` phường/xã của pickup trong danh mục GHN v3 |
| `SHIPPING_PICKUP_ADDRESS_LINE` | Địa chỉ cụ thể của điểm lấy hàng |

Cấu hình `nexus.shipping.default-warehouse-id` cùng `nexus.shipping.default-pickup` cho kho mặc định bằng các biến môi trường lấy hàng trên. Thêm `nexus.shipping.pickups[UUID]` để ánh xạ các kho khác sang `provinceCode`, `wardCode`, `addressLine`. Kho mặc định phải là UUID thực tế của kho `WH_001` trong môi trường đó; `WH_001` là mã kho, không phải UUID. Không đọc `inventory_db` từ shipment-service. `warehouseId` bỏ trống hoặc null dùng kho mặc định; UUID được chỉ định nhưng không có mapping trả 404; thiếu mapping/kho mặc định cần thiết trả lỗi cấu hình 503.

Từ contract `2.8.0`, request quote có thể kèm `pickupAddress` (`provinceCode`, `wardCode`, `line1` tối đa 255 ký tự). Khi có, shipment quote từ địa chỉ này thay cho mapping kho và kho mặc định. Commerce chỉ gửi `pickupAddress` khi seller đã đặt đủ tỉnh, phường và địa chỉ cụ thể cho kho riêng đang ACTIVE (`GET /internal/v1/inventory/warehouses/pickup`); đơn chỉ lưu `shipping_info.pickupAddress` khi hàng được giữ trong chính kho đó, nên tạo vận đơn dùng đúng điểm lấy hàng đã báo giá.

Cấu hình dưới đây chỉ dành cho bộ fake. UUID là fixture; mã Hà Nội `1000002` và phường `1000003` ở nhánh fake cũng là fixture, không phải xác nhận mã GHN thật. Không dùng fixture làm dữ liệu mặc định cho GHN sandbox hoặc production. Catalog local vẫn phải có SKU và giá trong bộ dữ liệu kiểm thử:

```properties
SHIPPING_CARRIER=FAKE
SHIPPING_DEFAULT_WAREHOUSE_ID=22222222-2222-4222-8222-222222222222
SHIPPING_PICKUP_PROVINCE_CODE=1000001
SHIPPING_PICKUP_WARD_CODE=1003646
SHIPPING_PICKUP_ADDRESS_LINE=Điểm lấy hàng fixture
SHIPPING_CATALOG_BASE_URL=http://127.0.0.1:8082
```

Catalog-service phải trả SKU hiện có và giá hiện tại qua API. Shipment gọi bằng OpenFeign `CatalogClient` với tên service `catalog-service`; `ShippingCatalogService` xác minh các SKU và tính subtotal. Khi `SHIPPING_CATALOG_BASE_URL` trống, Feign dùng Spring Cloud LoadBalancer/Eureka để tìm instance. Nếu cấu hình URL trực tiếp như ví dụ local trên, Feign dùng URL đó. Lượt gọi catalog dùng ngân sách deadline chung và breaker riêng của catalog, không retry tự động. Đổi transport này không thay đổi public contract shipping hoặc các mã lỗi catalog đã công bố.

Shipment không dùng giá browser gửi để xét miễn phí ship. `parcel` là số đo của kiện đã đóng gói, đơn vị gram/cm; không suy diễn số đo từ SKU hiện chưa có thuộc tính cân nặng/kích thước. Nếu thiếu thông tin kiện, hoàn thành dữ liệu chuẩn bị hàng trước khi gọi quote.

`insuranceValue` là giá trị khai bảo hiểm tùy chọn, độc lập với giá trị hàng của catalog. Chỉ chấp nhận số VND nguyên từ 0 đến 5.000.000; bỏ trống hoặc null gửi 0 sang GHN. Giá trị âm, có phần lẻ hoặc vượt giới hạn trả 400 `SHIPMENT_VALIDATION_FAILED` với field `insuranceValue`. Dịch vụ không tự lấy toàn bộ subtotal làm bảo hiểm, không tự cắt về 5.000.000 và không làm tròn giá trị người gọi gửi. Subtotal xét `FREE_SHIPPING_THRESHOLD` vẫn là toàn bộ giá SKU hiện tại nhân số lượng. Chỉ áp dụng miễn phí ship sau khi nhận preview có phí và ETA hợp lệ.

Mẫu body sau minh họa cấu trúc. `1000001` và `1003646` là các mã được GHN dùng trong ví dụ danh mục mới của Hồ Chí Minh/Phường Vũng Tàu; vẫn cần tải danh mục thật để xác minh trước khi gọi sandbox. Thay SKU, UUID kho, người nhận, địa chỉ và số đo bằng dữ liệu thực tế được phép sử dụng:

```json
{
  "toAddress": {
    "provinceCode": "1000001",
    "wardCode": "1003646",
    "line1": "Địa chỉ giao hàng đã xác minh",
    "recipientName": "Nguyễn Văn A",
    "recipientPhone": "0901234567"
  },
  "items": [
    {"skuId": "11111111-1111-4111-8111-111111111111", "quantity": 1}
  ],
  "parcel": {"weightGrams": 1200, "lengthCm": 30, "widthCm": 20, "heightCm": 15},
  "warehouseId": "22222222-2222-4222-8222-222222222222",
  "insuranceValue": 200000
}
```

## Kiểm tra tự động cục bộ

Từ `be-project-nexus`, chạy các lệnh dưới đây với Java 21. Reinstall `lib` trước khi chạy service nếu Maven cache chưa có shared artifacts tương ứng:

```powershell
.\shipment-service\mvnw.cmd -f shipment-service/pom.xml verify
.\config-service\mvnw.cmd -f config-service/pom.xml verify
.\api-gateway\mvnw.cmd -f api-gateway/pom.xml verify
node docs/api/check-contract.mjs
npx --yes @redocly/cli@2.63.0 lint docs/api/openapi/shipment-service.yaml
git grep -n -i -E 'ghn.*token|token.*ghn' -- '*.yml' '*.yaml'
```

Lệnh grep cuối không được có kết quả. Kiểm tra thêm diff để không có credential literal dưới tên khác. Tests cần kiểm chứng danh mục v3 và lọc `status`/`parent_id`, preview nhận đúng tên/cờ địa chỉ mới/cấu hình trả phí và kiểm hàng, không gọi create, kiểm tra `order_code` rỗng, retry/deadline, lỗi không retry, breaker mở và phục hồi HALF_OPEN. Tests MVC kiểm chứng envelope, validation người nhận, insuranceValue bỏ trống/null/0/giới hạn/âm/phần lẻ/vượt giới hạn và quyền/public paths. Mapping serviceType phải được kiểm tra tại 19.999g, 20.000g và 20.001g cho GHN lẫn fake, đồng thời JSON public chỉ dùng `STANDARD`/`HEAVY`. Các kiểm tra này không chứng minh token/ShopId thật đang hoạt động hoặc GHN sandbox thực sự trả báo giá.

`SHIPPING_API_TIMEOUT_MS` vẫn là deadline tổng, mặc định 3000ms cho toàn bộ thao tác. Một HTTP attempt GHN có ngân sách `min(GHN_HTTP_TIMEOUT_MS, thời gian còn lại)`, mặc định tối đa 1500ms; không chia trước deadline thành các phần bằng nhau theo số retries. `SHIPPING_RETRY_LIMIT=3` là giới hạn trên của số lần retry sau lần đầu, không bảo đảm một request treo được gọi đủ bốn lần. Call/backoff chỉ tiếp tục khi còn ngân sách. Retry chỉ dành cho lỗi mạng, timeout và HTTP tạm thời; input/token/ShopId sai không được retry. Breaker riêng từng carrier tính failure cuối cùng của một thao tác, không nhân failure theo số retry. Khi breaker mở, yêu cầu tiếp theo trả 503 nhanh mà không gọi carrier.

Chỉ danh mục province và ward đã tải thành công, validate cấu trúc và lọc active/đúng parent mới được cache thành danh sách bất biến trong shipment-service. Cache provinces dùng một key riêng; wards dùng key theo mã tỉnh. TTL mặc định 300000ms được tính từ lúc tải thành công. Hết TTL phải tải mới; lỗi tải/validate không được cache và không fallback sang dữ liệu đã hết hạn. Giá catalog, phí preview và ETA không nằm trong cache này. Gọi TC02/TC03 trước quote có thể làm ấm danh mục dùng lại cho cùng tỉnh khi TTL còn hiệu lực; pickup thuộc tỉnh khác vẫn cần danh mục riêng.

Sau khi cập nhật source/config cho hai property mới, build lại và khởi động lại shipment-service để dùng code mới. Config Server của dự án đọc native classpath: khi chạy từ JAR, cần build/resource-copy và khởi động lại config-service để phục vụ YAML mới, trừ khi môi trường IDE đã đồng bộ resources tương ứng. Thêm/sửa `.env` không tự nạp lại bean đã chạy. Không tăng deadline tổng tùy ý để che một lỗi đường truyền; ghi đúng cấu hình thực tế khi test.

## Smoke qua gateway với GHN sandbox

Khởi động config-service, discovery-service, catalog-service, shipment-service và gateway theo cấu hình môi trường. Lấy một JWT hợp lệ có `SHIPPING.QUOTE` bằng luồng đăng nhập hiện có; truyền JWT vào process environment, không ghi vào file request hoặc commit. Lưu body quote có người nhận và địa chỉ đã xác minh vào file JSON ngoài Git.

```powershell
$env:SHIPPING_SMOKE_ENABLED = 'true'
$env:SHIPPING_SMOKE_BASE_URL = 'http://127.0.0.1:8080'
$env:SHIPPING_SMOKE_CARRIER = 'GHN'
$env:SHIPPING_SMOKE_QUOTE_FILE = 'D:/KyOJT/tmp/shipping-quote-request.json'
node docs/testing/shipping-sandbox-smoke.mjs
```

`SHIPPING_SMOKE_TOKEN` phải được thiết lập trước bằng JWT có quyền. Script không đọc hoặc gửi GHN token; các API địa giới được gọi không có `Authorization`. Script kiểm tra mã v3 chuẩn, page, phường của hai tỉnh không bị trộn, missing provinceCode trả 400, carrier yêu cầu JWT, quote có fee/ETA hợp lệ và serviceType thuộc enum đúng ngưỡng cân nặng. Chỉ ghi tên bước, PASS/FAIL và mã kiểm chứng; không ghi token, tên người nhận, số điện thoại, địa chỉ, body hoặc thông tin lỗi upstream. Dùng `SHIPPING_SMOKE_CARRIER=FAKE` để kiểm tra wiring local không thay thế nghiệm thu sandbox.

Có thể dùng `SHIPPING_SMOKE_BASE_URL=http://127.0.0.1:8088` để đối chiếu trực tiếp shipment-service, nhưng kết quả đó không thay thế public paths qua gateway. Script cần tối thiểu hai tỉnh có dữ liệu để kiểm chứng lọc cha-con. Bộ fake có hai nhánh fixture đáp ứng điều kiện này.

Nếu TC05/TC18 nhận `SHIPMENT_CIRCUIT_OPEN` sau các lần timeout, dừng gửi dồn. Chờ hết open window đang cấu hình, mặc định 30 giây, rồi thử lại một lần; hoặc khởi động lại shipment-service khi đã cần nạp code/config mới. Khởi động lại làm mất cache và trạng thái breaker trong bộ nhớ, nên không được dùng để báo rằng breaker tự hồi phục thành công. TC18 vẫn phải trả 400 địa giới sai trước preview khi master data hợp lệ đã tải được; lỗi vận chuyển không phải bằng chứng hierarchy validation đã đạt.

Khi ghi bằng chứng nghiệm thu, lưu commit/build, mode, deadline/retry/breaker configuration và kết quả từng bước. Không ghi secret hoặc thông tin người nhận. Chỉ đánh dấu sandbox AC đạt sau khi chạy với `GHN` thật và nhận fee/ETA thật. Nếu thiếu credential hoặc môi trường, ghi rõ phần chưa chạy.

## Bàn giao checkout và giới hạn bằng chứng

Phạm vi người dùng đã chốt: #201 hoàn thành adapter/API shipping và tài liệu bàn giao; FE #150/#151 nhận yêu cầu chuyển hai cấp, còn sửa checkout BE thuộc issue riêng người dùng sẽ tạo. Issue đó sẽ xử lý district legacy trong preview/order, recipient/parcel và shippingFee/total tin cậy. Các dependency này đã được ghi nhận, không yêu cầu #201 chờ checkout triển khai xong. Không coi shippingFee null là miễn phí. Create/tracking/webhook thuộc #202/#203.

Lượt verify sau sửa HTTP attempt timeout/cache danh mục đạt **200 tests shipment-service và 7 tests config-service**, không failure/error/skipped. Test MVC/HTTP stub dùng deadline1000ms, cap250ms mỗi attempt và retryLimit2:3attempts, HTTP503 SHIPMENT_CARRIER_UNAVAILABLE sau**796ms**, `withinConfiguredBudget=true`; call sau breaker mở trả503 SHIPMENT_CIRCUIT_OPEN trong**7ms**, upstream calls trước/sau3/3. Mặc định runtime vẫn3000ms tổng/1500ms mỗi attempt; test override không thay mặc định này.

Test MVC cache quan sát master requests2sauwarmup và vẫn2sauquote hợp lệHTTP200; request ghép sai phường trả400, không reload danh mục hay gọi preview thêm. Các tests cache khác kiểm tra TTL, phân key theo tỉnh và không cache lỗi/stale fallback. Report lịch sử1008ms/budget1000ms/flagfalse được giữ nguyên, không đổi thành số đo mới. Public request/response không đổi bởi cache hoặc attempt timeout. Phần recipient/parcel cho checkout thuộc issue BE checkout riêng như [bảng đối chiếu #201](../api/shipping-contract-review-201.md).

Lượt JAR smoke trước đó dùng catalog/JWKS stubs, adapter FAKE và JWT ký trong bộ kiểm thử; DB/Flyway, Config Server và discovery được tắt để kiểm tra JAR độc lập. Master data GHN thật đã được probe và nhận HTTP200, nhưng **chưa xác minh TC05 quote sandbox thật thành công**. Người dùng đã báo TC05/TC18 timeout ở lượt trước sửa; không đổi chúng thành PASS chỉ vì tests mới đạt. Giữ response/status/thời gian mới sau khi nạp lại code/config; smoke FAKE hoặc tests fixture không thay thế evidence fee/ETA thật hay toàn luồng gateway triển khai.

Nguồn chính thức: [tỉnh/thành mới](https://developer.ghn.vn/vi/docs/master-data/get-province-new.md), [phường/xã mới](https://developer.ghn.vn/vi/docs/master-data/get-ward-new.md), [preview không tạo đơn](https://developer.ghn.vn/vi/docs/order/preview.md), [cờ địa chỉ mới và dữ liệu người nhận](https://developer.ghn.vn/vi/docs/order/create.md).

## Nhiều hãng vận chuyển ở checkout

Đặt `SHIPPING_CHECKOUT_CARRIERS=FAKE` (cùng `SHIPPING_QUOTE_CARRIER=GHN`) để `POST /api/v1/shipping/quotes` trả một lựa chọn cho mỗi hãng: GHN trước vì vẫn là nguồn danh mục địa chỉ, rồi FAKE. Mỗi phần tử có `carrierName` và `recommended`; phần tử đầu theo `SHIPPING_CARRIER_STRATEGY` là lựa chọn đề xuất. Hãng lỗi bị bỏ khỏi danh sách; chỉ khi mọi hãng lỗi mới trả lỗi của hãng đầu tiên.

Commerce `POST /api/v1/checkout/preview` trả `shippingOptions` là các hãng mọi seller đều giao được, phí cộng theo seller, ngày giao lấy ngày muộn nhất, cùng `selectedCarrier` là hãng đã tính vào `shippingFee`. Người mua gửi `carrier` trong preview và trong `POST /api/v1/orders`; carrier được lưu trong `shippingInfo` của đơn. Shipment đặt vận đơn bằng hãng người mua chọn nếu hãng đó đang bật, nếu không thì dùng `SHIPPING_BOOKING_CARRIER`. Khi `SHIPPING_CHECKOUT_CARRIERS` trống, hành vi cũ giữ nguyên: một hãng báo giá và hãng đặt vận đơn theo cấu hình, kể cả cách dùng phí GHN với vận đơn FAKE.
