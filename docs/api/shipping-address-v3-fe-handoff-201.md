# #201 — Hướng dẫn FE chuyển sang API tỉnh/thành, phường/xã mới

Cập nhật: **10/11/2026**. Nhánh BE: `ngothanhtu8989/feature/201-be-carrier-adapter-and-ghn-shipping-quote`.

Tài liệu phản ánh code trong working tree của nhánh #201, đối chiếu với FE hiện có; không có nghĩa thay đổi đã được merge hoặc triển khai. Nguồn contract là [OpenAPI shipment-service, phiên bản 2.1.0](openapi/shipment-service.yaml). FE dùng tài liệu này cho #150/#151; theo phạm vi người dùng chốt ngày 10/11/2026, phần sửa checkout BE sẽ thuộc issue riêng do người dùng tạo, không phải điều kiện chờ triển khai để hoàn thành #201.

**Thay đổi chính:** địa chỉ vận chuyển chuyển từ ba cấp **Tỉnh/Thành phố → Quận/Huyện → Phường/Xã** sang hai cấp **Tỉnh/Thành phố → Phường/Xã** theo danh mục GHN v3. FE bỏ dropdown quận/huyện và tải phường/xã trực tiếp theo `provinceCode`.

**Dependency trước khi chạy checkout hoàn chỉnh:** commerce preview/order hiện vẫn bắt buộc `shippingAddress.district` và nhận tên địa giới, chưa nhận cặp mã v3. Phần này được chuyển sang issue checkout BE riêng; #201 bàn giao shipping contract và ghi rõ dependency ở mục 5. Người dùng xác nhận đã gửi yêu cầu cập nhật INPUT/SUBTASKS/AC hai cấp cho FE #150.

## 1. Những gì thay đổi so với contract ba cấp

| Nội dung | Trước đây | Nhánh #201 hiện tại |
|---|---|---|
| Giao diện địa chỉ | Chọn tỉnh → quận → phường | Chọn tỉnh → phường |
| Tỉnh/thành | `GET /api/v1/shipping/provinces` | Giữ URL, dùng danh mục mới |
| Quận/huyện | `GET /api/v1/shipping/districts?provinceCode=...` | Bỏ operation này; FE bỏ lời gọi |
| Phường/xã | `GET /api/v1/shipping/wards?districtCode=...` | `GET /api/v1/shipping/wards?provinceCode=...` |
| Mã địa giới | Mã trong danh mục GHN ba cấp | `_id` của danh mục GHN v3, BE trả trong `code` dạng string |
| Địa chỉ gửi vào shipping quote | Có `districtCode` | Dùng `provinceCode`, `wardCode`; bỏ `districtCode` |
| Quyền đọc danh mục | FE hiện gửi `auth: true` | Hai API địa giới public; FE dùng `auth: false` |

Không tái sử dụng mã cũ như `202`, `20308` từ mock hoặc địa chỉ đã lưu như thể đó là mã của danh mục mới. Nếu dữ liệu cũ chưa có mapping đã được xác minh, yêu cầu người dùng chọn lại tỉnh/phường; không dò theo tên hoặc tự suy ra mã.

## 2. Contract FE cần dùng

### 2.1. URL và quyền truy cập

FE gọi qua gateway, local mặc định `http://localhost:8080`; cấu hình origin bằng cơ chế API hiện có của FE.

| Method | Path | Xác thực |
|---|---|---|
| GET | `/api/v1/shipping/provinces` | Public, không gửi Bearer |
| GET | `/api/v1/shipping/wards?provinceCode=<code>` | Public, không gửi Bearer |
| GET | `/api/v1/carriers` | JWT hợp lệ và quyền `SHIPPING.QUOTE` |
| POST | `/api/v1/shipping/quotes` | JWT hợp lệ và quyền `SHIPPING.QUOTE` |

Public nghĩa là không cần JWT; một Bearer sai/hết hạn được gửi kèm vẫn có thể gây 401. Trong `requestApi` hiện tại, dùng `auth: false` cho hai API danh mục. Trang checkout vẫn giữ yêu cầu đăng nhập để đọc cart, preview và đặt hàng.

FE chỉ gọi API của hệ thống. Backend giữ GHN Token/ShopId và xử lý giao tiếp với GHN.

### 2.2. Response địa giới

Các ví dụ dưới đây dùng **fixture FAKE của nhánh #201**, chỉ minh họa cấu trúc và quan hệ cha-con. Khi chạy GHN thật, tải mã/tên từ response trong môi trường đó; không hardcode fixture làm dữ liệu thật.

`GET /api/v1/shipping/provinces`:

```json
{
  "code": 0,
  "message": "response success",
  "data": [
    { "code": "1000001", "name": "Hồ Chí Minh" },
    { "code": "1000002", "name": "Hà Nội" }
  ],
  "page": {
    "number": 0,
    "size": 2,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

`GET /api/v1/shipping/wards?provinceCode=1000001`:

```json
{
  "code": 0,
  "message": "response success",
  "data": [
    { "code": "1003646", "name": "Phường Vũng Tàu" }
  ],
  "page": {
    "number": 0,
    "size": 1,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

Quy tắc đọc dữ liệu:

- Mỗi phần tử chỉ có `code` và `name`. `code` là **string**, khớp `^[1-9][0-9]{0,8}$`: từ 1–9 chữ số, không có số 0 đầu. Không chuyển sang number hoặc dùng tên làm ID.
- `code` ở cấp ngoài cùng bằng số `0` là mã thành công; khác với `data[i].code` là mã địa giới.
- Lấy mảng ở `data`, giữ `page` cùng cấp. `requestApi` hiện đã trả envelope; `parseShippingLocalities` hiện có có thể tiếp tục đọc `response.data` và `response.page`.
- Danh mục là toàn bộ kết quả trong một trang logic, không có query phân trang. Danh sách rỗng có `data: []`, `page.size=1`, `totalElements=0`, `totalPages=0`.
- Backend đã lọc địa giới đang hoạt động (`status=1`) và kiểm tra quan hệ cha-con. FE không cần đọc các field GHN nội bộ như `_id`, `status`, `parent_id`.
- Tỉnh không tồn tại trả lỗi 400; không được biến mọi lỗi tải danh mục thành mảng rỗng.

### 2.3. Lỗi liên quan tới form

Lỗi có Content-Type `application/problem+json`, đọc `code`, `detail` và `errors[]` ở cấp ngoài cùng. `errors[]`, khi có, dùng `{field, code, detail}`.

| HTTP | Mã lỗi thường gặp | FE xử lý |
|---|---|---|
| 400 | `SHIPMENT_INVALID_PARAMETER` | Query thiếu/sai `provinceCode`; kiểm tra lời gọi API |
| 400 | `SHIPMENT_INVALID_ADDRESS` | Tỉnh/phường không tồn tại hoặc sai quan hệ; yêu cầu chọn lại |
| 400 | `SHIPMENT_VALIDATION_FAILED` | Gắn `errors[].detail` vào field quote tương ứng |
| 401/403 | `SHIPMENT_UNAUTHENTICATED`, `SHIPMENT_FORBIDDEN` | Kiểm tra đăng nhập/quyền khi gọi quote hoặc carriers |
| 422 | `SHIPMENT_NO_SERVICE` | Thông báo tuyến chưa có dịch vụ phù hợp |
| 502 | `SHIPMENT_INVALID_CARRIER_RESPONSE` | Báo lỗi tải dữ liệu, cho thử lại |
| 503 | `SHIPMENT_CARRIER_UNAVAILABLE`, `SHIPMENT_CIRCUIT_OPEN` | Báo tạm không khả dụng; không coi là miễn phí ship |

Gateway có thể trả mã chung như `UNAUTHENTICATED`, `FORBIDDEN`, `RATE_LIMITED`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT` trước khi request tới shipment-service. Danh sách đầy đủ nằm trong OpenAPI.

## 3. Cách sửa luồng FE

1. Khi mở form, tải provinces. Giữ `provinceCode` và `wardCode` trong state dưới dạng string; tên lấy từ các option để hiển thị.
2. Chỉ bật dropdown phường/xã và query wards khi đã chọn tỉnh. Gọi `getWards(provinceCode)`.
3. Khi đổi tỉnh, xóa `wardCode`, bỏ địa chỉ đã xác nhận và báo giá cũ. Trong lúc tải, không cho chọn phường của tỉnh trước.
4. Query key của wards phải chứa `provinceCode`. Đổi namespace cache địa giới sang v3 để tránh dùng lại mã/fixture cũ. Giữ `AbortSignal` và các kiểm tra request cũ để response về trễ không ghi đè lựa chọn mới.
5. Bỏ validation quận/huyện của form shipping mới. Chỉ coi địa chỉ hợp lệ khi tỉnh có trong danh mục và phường nằm trong danh sách của chính tỉnh đang chọn.
6. Khi tỉnh/phường hoặc dữ liệu báo giá thay đổi, vô hiệu báo giá trước đó và tải lại theo contract tích hợp đã thống nhất ở mục 5. Giữ kiểm tra cart/session và cơ chế idempotency hiện có của đặt hàng.

Ví dụ thay hàm wards trong `service/checkoutService.ts`, sử dụng lại HTTP boundary/parser hiện có:

```ts
export const getWards = async (
  provinceCode: string,
  options: ServiceRequestOptions = {},
): Promise<ShippingLocality[]> => {
  if (!/^[1-9][0-9]{0,8}$/.test(provinceCode)) {
    throw createInvalidResponseError("A valid provinceCode is required");
  }
  const response = await requestApi<unknown>("GET", WARDS_PATH, undefined, {
    ...options,
    auth: false,
    params: { provinceCode },
  });
  return parseShippingLocalities(response);
};
```

Đổi `getProvinces` sang `auth: false`, bỏ `DISTRICTS_PATH`/`getDistricts`, rồi cập nhật các caller. Đây là đoạn hướng dẫn sửa, chưa được áp dụng vào FE.

## 4. Địa chỉ và body báo giá hiện tại

Phần này mô tả **contract của API shipping trong #201**, để FE biết request nào được chấp nhận. API nhận recipient/parcel thực tế; việc cung cấp các dữ liệu này trong luồng checkout và tích hợp với preview/order thuộc issue checkout BE riêng. Dependency đã được bàn giao, không yêu cầu #201 tự triển khai commerce checkout; xem [bảng đối chiếu #201](shipping-contract-review-201.md).

Mapping từ form FE sang `POST /api/v1/shipping/quotes`:

| Dữ liệu FE | Field shipping quote |
|---|---|
| Tỉnh đã chọn | `toAddress.provinceCode` |
| Phường đã chọn | `toAddress.wardCode` |
| `addressLine` | `toAddress.line1` |
| `recipientName` | `toAddress.recipientName` |
| `phoneNumber` | `toAddress.recipientPhone` |
| Quận/huyện | Bỏ, không gửi `districtCode` |

Ví dụ cấu trúc request; thay SKU, người nhận, địa chỉ và số đo bằng dữ liệu thực tế. `warehouseId: null` chỉ dùng được khi BE đã cấu hình kho mặc định hợp lệ.

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
    { "skuId": "11111111-1111-4111-8111-111111111111", "quantity": 1 }
  ],
  "parcel": {
    "weightGrams": 1200,
    "lengthCm": 30,
    "widthCm": 20,
    "heightCm": 15
  },
  "warehouseId": null,
  "insuranceValue": 200000
}
```

Các ràng buộc cần đối chiếu khi tích hợp:

- Năm field trong `toAddress` và `parcel` đều bắt buộc. `line1` tối đa 255 ký tự; `recipientName` tối đa 1024 ký tự. FE hiện cho `addressLine` tới 500 ký tự nên cần thu hẹp khi dùng quote.
- `recipientPhone` khớp `^(0[0-9]{9}|\+84[0-9]{9})$`, ví dụ `0901234567` hoặc `+84901234567`. FE hiện cho định dạng rộng hơn; cần chuẩn hóa có kiểm tra hoặc validate theo contract mới.
- `items`: 1–100 phần tử; `skuId` là UUID; `quantity` nguyên từ 1–999. `parcel`: cân nặng nguyên 1–50.000 gram, mỗi kích thước nguyên 1–200 cm. Đây là số đo kiện đã đóng gói; không lấy ví dụ làm mặc định, không tự suy diễn từ SKU chưa có dữ liệu.
- `warehouseId` là UUID tùy chọn, bỏ trống/null dùng kho mặc định của BE. UUID chưa có mapping trả `404 SHIPMENT_WAREHOUSE_NOT_FOUND`.
- `insuranceValue` tùy chọn, bỏ trống/null tương đương 0; VND nguyên từ 0–5.000.000. Không mặc định bằng subtotal.
- Response quote là envelope danh sách; mỗi phần tử có `carrier`, `serviceType`, `fee`, `estimatedDeliveryAt`. `fee` là JSON number VND; ETA là timestamp ISO-8601 có timezone.
- `serviceType` là `STANDARD` cho kiện dưới 20.000g, `HEAVY` từ 20.000g; các ID provider `2`/`5` chỉ dùng trong BE.
- Miễn phí ship chỉ được áp dụng sau quote hợp lệ theo chính sách BE. `shippingFee: null` ở commerce preview nghĩa là chưa có phí, không phải 0.

Để hiển thị validation quote, mở rộng mapping lỗi hiện tại để nhận `SHIPMENT_VALIDATION_FAILED` và prefix `toAddress.`; map `line1 → addressLine`, `recipientPhone → phoneNumber` trong UI. Các lỗi `parcel.*` phải được xử lý tại nơi cung cấp số đo kiện, không gán vào dropdown địa giới.

BE đang đọc danh mục GHN v3 và gọi endpoint preview ở path `/shiip/public-api/v2/shipping-order/preview` với các cờ địa chỉ mới. FE không cần đổi sang một URL GHN quote v3. Quote chỉ báo giá, không tạo vận đơn; create/tracking/webhook thuộc #202/#203.

## 5. Phần checkout BE chuyển sang issue riêng

Hiện `service/checkoutService.ts` chưa gọi shipping quote. Nó gọi `POST /api/v1/checkout/preview` và `POST /api/v1/orders` bằng `createOrderBody()`, chỉ gửi tên trong `shippingAddress.city/district/ward`. Ba mã địa giới hiện chỉ được giữ ở state FE.

Trong commerce, `CreateOrderRequest.ShippingAddress.district` vẫn có `@NotBlank`; OpenAPI và generated type FE cũng còn field này. Vì vậy xóa dropdown quận rồi gửi `district: ""` sẽ không tương thích backend hiện tại. Không điền quận giả, lấy tên tỉnh thay quận hoặc suy ngược quận từ phường mới.

Theo quyết định phạm vi của người dùng, các công việc sau được ghi để issue checkout BE riêng phối hợp với FE #150/#151 trước cutover:

- Contract preview/order nhận, validate và lưu địa chỉ hai cấp; cập nhật DTO, OpenAPI, snapshot và generated types liên quan.
- Cách chuyển `provinceCode`, `wardCode`, recipient và `line1` tới shipment, cùng nguồn `parcel` thực tế và kho lấy hàng.
- Nơi gọi quote, cách chọn carrier/serviceType và cách BE tính lại shipping fee/total để preview khớp đơn hàng; không chỉ cộng một phí lấy ở browser rồi coi order đã đồng bộ.

Các điểm trên là dependency của checkout, không phải việc triển khai còn thiếu của adapter/API shipping #201. Người dùng sẽ tạo issue checkout BE riêng; chưa có ID nên không gán một số issue giả. FE có thể sửa/test danh mục và form hai cấp trước; việc bật checkout hoàn chỉnh sẽ được kiểm chứng khi commerce tương thích.

## 6. Danh sách file FE cần cập nhật

Đường dẫn dưới đây tương đối với `fe-project-nexus/`. Đây là checklist cho FE, chưa có thay đổi implementation trong các file này từ tài liệu bàn giao.

| File | Nội dung cần sửa |
|---|---|
| `scripts/generate-types.mjs` | Thêm shipment-service vào danh sách generate, ví dụ `["shipment-service", "shipmentGeneratedType.ts"]`; generate DTO từ OpenAPI, không sửa file generated bằng tay |
| `types/checkoutType.ts` | Form shipping dùng hai mã tỉnh/phường; đối chiếu `ShippingLocality` với type generate. Sửa `CheckoutDestination` cùng contract commerce ở mục 5 |
| `service/checkoutService.ts` | Bỏ districts; wards nhận provinceCode; hai danh mục `auth: false`; cập nhật comment contract và parser/quote mapping nếu tích hợp |
| `configs/queryKeys.ts` | Bỏ key districts; key wards theo provinceCode; tách cache v3 khỏi dữ liệu cũ |
| `features/CheckoutPage.tsx` | Bỏ query/state quận, sửa dependencies, reset phường/quote/review khi đổi tỉnh; giữ session/cart guards |
| `components/AddressForm.tsx` | Bỏ dropdown, props, retry/loading/error state của district; wards phụ thuộc provinceCode |
| `features/checkoutSchema.ts` | Bỏ validation/lookup district cho form mới; kiểm tra quan hệ tỉnh/phường; sửa giới hạn địa chỉ/điện thoại và error aliases khi dùng quote |
| `postman/mocks/checkout-api/default.js` | Fixtures hai cấp, bỏ route districts, wards lọc theo tỉnh, public không Bearer |
| `postman/simulations/checkout.context.yaml` | Cập nhật assumptions/evidence sang hai cấp; phân biệt mock với BE thật |
| `tests/checkout-service.test.ts`, `tests/checkout-schema.test.ts`, `tests/checkout.spec.ts` | Đổi URL/query/auth/fixtures; test reset tỉnh, phường sai tỉnh và response cũ về trễ |
| `tests/place-order-service.test.ts`, `tests/place-order.spec.ts` | Cập nhật địa chỉ cùng commerce; giữ idempotency và hành vi retry đặt hàng |
| `docs/features/checkout.md`, `docs/api/api-contract.md` | Sửa mô tả địa giới ba cấp/protected thành hai cấp/public và ghi dependency commerce |

`configs/api.ts` hiện đã xử lý envelope và Problem Details; tiếp tục sử dụng HTTP boundary này. Generated DTO mới được dùng qua alias/model ứng dụng theo quy tắc FE hiện hành.

## 7. Checklist kiểm tra sau khi FE triển khai

- [ ] Chỉ còn hai dropdown tỉnh/thành và phường/xã; không phát sinh request tới `/shipping/districts`.
- [ ] Hai API danh mục gọi được không có Authorization; wards gửi đúng `provinceCode`, không gửi `districtCode`.
- [ ] Chọn hai tỉnh khác nhau chỉ hiện phường của tỉnh đang chọn; đổi tỉnh reset phường và phí cũ.
- [ ] Đổi tỉnh nhanh, response cũ về trễ không ghi đè danh sách mới; dữ liệu cache/mã ba cấp không được dùng lại.
- [ ] Thiếu tỉnh/phường/dòng địa chỉ báo lỗi từng field và không submit; xử lý được danh sách rỗng, 400, 502, 503 và retry tải lại.
- [ ] Preview/order chạy với commerce contract hai cấp đã thống nhất; không điền district giả và không đổi phí null thành miễn phí.
- [ ] Nếu gọi quote: recipient/parcel hợp lệ, `line1` đúng field, chỉ có mã v3; phí/total/địa chỉ đơn hàng khớp preview.
- [ ] Giữ hành vi cart rỗng, đăng nhập, idempotency, lỗi 409 và giao diện 375×812 của checkout hiện tại.

Sau khi sửa, chạy tại `fe-project-nexus/` theo scripts của dự án:

```powershell
npm run generate:types
npm run generate:types:check
npm run lint
npm run typecheck
npm run test:unit
npm run test:checkout
npm run build
```

Đây là các bước FE cần chạy sau implementation, không phải kết quả đã chạy khi viết tài liệu. Mock/FAKE pass chưa chứng minh tích hợp GHN sandbox thật hoặc gateway thật. Bằng chứng hiện có và cách smoke nằm trong [shipping-quotes.md](../testing/shipping-quotes.md); tài liệu đó hiện ghi GHN sandbox thật chưa được chạy.

## 8. Nguồn đối chiếu và nội dung sửa issue

- [OpenAPI shipment-service](openapi/shipment-service.yaml): contract wire của nhánh #201.
- [ShippingController](../../shipment-service/src/main/java/com/group5/shipmentservice/shipping/controller/ShippingController.java) và [ShippingAddressService](../../shipment-service/src/main/java/com/group5/shipmentservice/shipping/service/ShippingAddressService.java): endpoints và quan hệ tỉnh/phường.
- [ShippingAddressRequest](../../shipment-service/src/main/java/com/group5/shipmentservice/shipping/dto/request/ShippingAddressRequest.java), [ShippingQuoteRequest](../../shipment-service/src/main/java/com/group5/shipmentservice/shipping/dto/request/ShippingQuoteRequest.java): validation quote hiện tại.
- [FE checkoutService](../../../fe-project-nexus/service/checkoutService.ts), [CheckoutPage](../../../fe-project-nexus/features/CheckoutPage.tsx), [checkoutSchema](../../../fe-project-nexus/features/checkoutSchema.ts): luồng FE ba cấp cần chuyển đổi.
- [Commerce CreateOrderRequest](../../commerce-service/src/main/java/com/group5/commerceservice/order/dto/request/CreateOrderRequest.java): field district còn bắt buộc ở preview/order.
- [Đối chiếu contract #201](shipping-contract-review-201.md): recipient/parcel còn cần chốt với checkout.

Issue #201 đã được người dùng cung cấp bản cập nhật 10 AC và 7 subtask theo mô hình hai cấp. Người dùng xác nhận đã gửi các block B1/B2/B3 cho FE #150: INPUT dùng hai API public, form giữ provinceCode/wardCode dạng string, bỏ district, đổi tỉnh reset ward/quote và bỏ response cũ về trễ. Việc triển khai checkout BE được chuyển sang issue riêng do người dùng tạo.

## 9. Trạng thái bàn giao và phạm vi đã chốt

- #201 thực hiện adapter GHN/fake, quote, master data v3, timeout/retry/breaker, chính sách miễn phí ship, public paths, OpenAPI và tài liệu bàn giao này.
- Yêu cầu cập nhật FE đã được người dùng gửi cho FE #150 theo các block B1/B2/B3 trong cuộc trao đổi. Đây là bằng chứng bàn giao do người dùng cung cấp; không phải kết quả agent tự gửi message, không đồng nghĩa FE đã triển khai hoặc kiểm thử xong.
- Các thay đổi FE thuộc #150/#151. Các thay đổi commerce preview/order, bỏ district bắt buộc ở checkout BE, nguồn recipient/parcel và trusted shipping fee/total thuộc issue checkout BE riêng; ID sẽ do người dùng bổ sung sau khi tạo.
- Ghi nhận dependency này hoàn thành phần bàn giao trong phạm vi #201 đã chốt. #201 không phải chờ code checkout BE hoặc FE triển khai xong mới nghiệm thu shipping.
- Nghiệm thu GHN sandbox thật, public API qua gateway thật và timeout/breaker vẫn được đánh giá theo evidence shipping riêng. Việc tách checkout không biến fixture hoặc mock thành bằng chứng live.
