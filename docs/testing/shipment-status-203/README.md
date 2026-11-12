# Bộ kiểm thử và evidence Shipment status — #203

Bộ Postman có **102 request trong 10 folder**, bao phủ đủ tám AC, tracking, manual update, override, phân quyền và poller. Environment chỉ còn **bốn ô access token**. URL, secret callback local và fixture đều đã cấu hình trong collection; buyer ID và actor ID tự lấy từ JWT.

Kiểm tra lại ngày 12/11/2026 sau khi đồng bộ #206: **102 request, 483 assertion đều đạt**, SQL đạt **36 case và 5 kiểm tra consumer**. Collection đã sinh lại với `/tracking` giữ object tổng hợp và `/history` trả audit phân trang. Xem [báo cáo kiểm tra nhánh](../shipment-status-203-review-2026-11-12.md).

## 1. Bạn chỉ cần đăng nhập, import và chạy

Khi môi trường đã báo `READY`, thực hiện ba bước:

1. Tự đăng nhập lấy access token của Admin, Support Staff, Buyer và một Buyer khác. Dùng access token của realm `nexus`, audience `nexus-services`; không dùng ID token hoặc refresh token.
2. Import hai file dưới đây vào Postman, chọn environment **Nexus #203 | Your four access tokens**, rồi dán token vào đúng bốn ô.
3. Chạy collection một iteration theo thứ tự folder `00` đến `09`, hoặc gửi từng request theo thứ tự để chụp evidence. Folder `00 Setup` tự nạp lại SQL và gắn buyer theo token trước mỗi lượt.

| File import | Công dụng |
| --- | --- |
| [issue203-shipment-status.postman_collection.json](postman/issue203-shipment-status.postman_collection.json) | Toàn bộ request, assertion, cấu hình URL và dữ liệu callback |
| [issue203-local.postman_environment.json](postman/issue203-local.postman_environment.json) | Bốn access token do bạn tự điền |

| Ô environment | Token cần điền |
| --- | --- |
| `accessTokenAdmin` | Vai trò `ADMIN`, có `SHIPPING.VIEW_SHIPMENT`, `SHIPPING.UPDATE_STATUS`, `SHIPPING.MANUAL_OVERRIDE` |
| `accessTokenSupport` | Vai trò `SUPPORT_STAFF`, có ba privilege shipping như Admin |
| `accessTokenBuyer` | Buyer có `SHIPPING.VIEW_SHIPMENT`; collection tự lấy `sub` để seed ownership |
| `accessTokenOtherBuyer` | Buyer khác, có `SHIPPING.VIEW_SHIPMENT` và `sub` khác token buyer ở trên |

Không cần điền `buyerId`, actor ID, URL, shop ID, callback secret hoặc thông số DB/Kafka. Collection tự giải mã `sub` để chuẩn bị fixture; service vẫn kiểm tra chữ ký, issuer, audience và quyền của JWT. Việc giải mã trong Postman không thay thế xác thực của service.

Hướng dẫn login PKCE: [postman-pkce.md](../../security-keycloak/postman-pkce.md). Bộ này không tự đăng nhập, không gửi mật khẩu, không tạo user và không lưu token trong file được commit. Giữ token ở local, tránh chụp màn hình có giá trị bearer token.

## 2. Người khác chạy bộ này như thế nào

Checkout cùng nhánh #203. Máy cần Docker Desktop đang chạy, Node.js, Java 21 và Maven; Keycloak realm `nexus` phải đang hoạt động để người chạy tự login. Từ `be-project-nexus`, chỉ chạy một lệnh:

```powershell
./docs/testing/shipment-status-203/start.ps1
```

Script tự thực hiện:

- Build JAR khi chưa có; dùng `-Rebuild` khi muốn build lại từ source mới.
- Khởi động PostgreSQL 18 và Kafka riêng cho #203 bằng [compose.yaml](compose.yaml), tạo hai topic cần thiết.
- Sinh cấu hình service, khởi chạy shipment-service và GHN companion ở chế độ nền, đợi health sẵn sàng.
- Nạp SQL fixture, chuẩn bị file log evidence và tái tạo hai file Postman.

Khi terminal in `READY`, người chạy thực hiện ba bước ở mục 1. Không phải sửa file `.env` hoặc biết user/password DB để chạy bộ này. Cấu hình hạ tầng dùng chung nằm trong `start.ps1` và `compose.yaml`.

Nếu Keycloak của người chạy dùng issuer khác mặc định `http://localhost:18080/realms/nexus`, truyền đúng issuer một lần lúc khởi động:

```powershell
./docs/testing/shipment-status-203/start.ps1 -Issuer 'http://localhost:18080/realms/nexus'
```

Chỉ cần `-JwksUrl` khi địa chỉ JWKS không theo `<issuer>/protocol/openid-connect/certs`. Các tùy chọn này thuộc cấu hình khởi động; environment Postman vẫn chỉ có bốn token.

| Loại cấu hình | Ai điền / được chuẩn bị ở đâu |
| --- | --- |
| Bốn access token | Mỗi người tự login và dán vào Postman environment local |
| URL, shop ID, callback secret, timestamp, fixture ID | Collection variables có sẵn; không cần nhập lại |
| Buyer ID và actor ID | Collection tự lấy từ JWT `sub` |
| DB, Kafka, migration, log, GHN mock, consumer và poller | `start.ps1` + `compose.yaml` tự khởi động và cấu hình |
| Issuer/JWKS nếu local khác | Tham số khởi động; không thêm vào Postman environment |

Dừng các tiến trình và container của bộ kiểm thử:

```powershell
./docs/testing/shipment-status-203/stop.ps1
```

## 3. Môi trường và phạm vi bằng chứng

| Thành phần | Địa chỉ / tên dành riêng cho #203 |
| --- | --- |
| Shipment-service thật | `http://localhost:18088` |
| GHN companion và evidence | `http://127.0.0.1:9903` |
| PostgreSQL 18 | Container `issue203-postgres`, cổng host `15433`, database `shipment_db` |
| Kafka | Container `issue203-kafka`, cổng host `39092` |
| Log và evidence JSON | [evidence/](evidence/), tạo tự động và được gitignore |

Service, PostgreSQL, Kafka, controller và `GhnAdapter` đều chạy thật. Companion mô phỏng API phía GHN, gửi `OrderCancelled` lên broker thật và đọc history/outbox/processed marker từ DB thật. `/__test/*` là đường dẫn của companion local, không được thêm vào production API.

Bộ này kiểm tra trực tiếp shipment-service ở cổng `18088`. Gateway `8080` và service local `8088` hiện có không bị thay đổi; kết quả không đại diện cho kiểm thử routing qua gateway. Contract routing vẫn có kiểm tra tài liệu/inventory riêng ở `AC8-01`. GHN được mô phỏng cục bộ, nên không coi ảnh này là bằng chứng gọi GHN sandbox thật.

Fixture dùng shipment `20300000-0000-4000-8000-*`, order `20310000-0000-4000-8000-*`, tracking `S203-*`. Seed/cleanup chỉ tác động namespace này trong DB riêng của #203, không dùng `TRUNCATE` hoặc thay đổi user Keycloak. Polling của mọi fixture được park đến tương lai; testcase poller chỉ arm shipment `109`.


## 4. Thứ tự chạy và chụp evidence

1. Điền đủ bốn token trước khi chạy `00 Setup`; buyer của SQL fixture sẽ tự khớp token của bạn.
2. Chạy folder `00` rồi `01` đến `09`. Các request `-E` đọc evidence thật và đợi consumer/poller hoàn tất trong thời gian giới hạn.
3. Chụp response và tab Test Results theo bảng AC ở mục 5. Không chỉ chụp HTTP 200 của request publish Kafka: cần chụp request `-E` chứng minh consumer đã hoàn tất.
4. Muốn chạy lại toàn bộ, chạy lại `00 Setup` rồi chạy từ đầu. Không reset trong khi request hoặc Kafka delivery của lượt cũ còn đang xử lý. Seed tự kiểm tra consumer đã hết lag trước khi xóa marker của lượt trước.
5. Giữ companion trong suốt lượt chạy để bộ đếm gọi GHN mock và log baseline nhất quán. Seed ghi nhận vị trí cuối file log; `AC6-03-E` chỉ đếm WARN đúng order/event của lượt hiện tại và trả cả dòng WARN thực.

Folder `00`–`06` và `08` không cần JWT. Folder `07` và `09` cần token. Testcase `CANCEL-FAIL-01` ở folder `09` dùng PATCH có xác thực để kiểm tra rollback khi carrier từ chối; không tạo event Kafka lỗi gây chặn lượt chạy tiếp theo. AC5, AC6 và AC7 vẫn dùng consumer Kafka thật ở folder `05`.

Với Postman CLI, dùng bản environment local đã điền token:

```powershell
postman collection run docs/testing/shipment-status-203/postman/issue203-shipment-status.postman_collection.json -e docs/testing/shipment-status-203/postman/issue203-local.postman_environment.json --no-report-events
```

## 5. Ma trận AC → testcase → ảnh evidence

Tên testcase trong cột thứ hai xuất hiện ở đầu request trong collection. Ảnh evidence nên chứa tên request, URL, HTTP status, response body và tab Test Results. Đối với DB/log, giữ lại `shipmentId`, `orderId` hoặc `eventId` để người review đối chiếu đúng fixture.

| AC của issue | Testcase / phần chạy | Nội dung bắt buộc hiện trên evidence | Gợi ý tên ảnh |
| --- | --- | --- | --- |
| **AC1. GHN status ánh xạ về một trạng thái nội bộ; mỗi thay đổi có một tracking row và một Shipment* event** | Folder `01 AC1 GHN mapping`: `AC1-M01`…`AC1-M23` và request `-E` tương ứng | Callback 200; trạng thái nội bộ đúng bảng mapping; `historyCount=1`, `eventCount=1`; historyId và shipmentVersion trong event khớp history/shipment; topic `nexus.shipment.events`. Chụp tổng kết cả 23 ca và ít nhất một cặp callback + evidence chi tiết | `AC1_mapping_23_cases.png`, `AC1_picked_history_event.png` |
| **AC2. Chữ ký sai trả 401 và không thay đổi gì** | Folder `02 AC2 signature`: `AC2-01`, `AC2-02`, `AC2-03`, `AC2-04-E` | 401 `application/problem+json`, code `SHIPMENT_INVALID_SIGNATURE`; status vẫn `READY_TO_SHIP`, version `0`, history/event/receipt `0`; số lần gọi carrier không tăng | `AC2_bad_signature_401.png`, `AC2_unchanged_db.png` |
| **AC3. Cùng webhook gửi hai lần chỉ thay đổi một lần** | Folder `03 AC3 dedupe`: `AC3-01` rồi `AC3-02`, cuối cùng `AC3-04-E` | Cả hai callback 200 với `{code:200,message:"Success"}`; status `SHIPPED`, history/event chỉ `1`, version `1`. Các ca JSON khác format/timestamp cùng instant và status mới không đổi đích cũng không tăng history/event | `AC3_first_and_duplicate_200.png`, `AC3_one_history_one_event.png` |
| **AC4. `DELIVERED → IN_TRANSIT` trả 409** | Folder `04 AC4 backward`: `AC4-01`, `AC4-02-E`; phần manual có thêm kiểm tra PATCH/override | 409 code `SHIPMENT_INVALID_TRANSITION`; status vẫn `DELIVERED`, version `0`, history/event/receipt không có bản ghi mới | `AC4_backward_409.png`, `AC4_delivered_unchanged.png` |
| **AC5. OrderCancelled trước pickup gọi hủy carrier, chuyển CANCELLED và ghi một tracking row** | Folder `05 AC5-AC7 cancellation`: `AC5-01`, `AC5-02`, `AC5-03-E`, fixture `104` | Event Kafka có key là orderId; consumer processed `1`; status `CANCELLED`; mock ghi nhận đúng một lời gọi từ `GhnAdapter` tới API cancel với trackingCode; history `1`, `ShipmentCancelled` event `1`, source `SYSTEM`. Duplicate không tăng hiệu ứng | `AC5_cancel_before_pickup.png`, `AC5_carrier_call_and_history.png` |
| **AC6. OrderCancelled sau pickup không thay đổi shipment và ghi một WARN cho Support** | Cùng folder `05`: `AC6-01`, `AC6-02`, `AC6-03-E`, fixture `105` | Status vẫn `SHIPPED`, version `0`, history/event `0`, carrier cancel call `0`, processed `1`; log có đúng một `Support action required: OrderCancelled arrived after pickup` cho orderId/eventId của ca này; duplicate không thêm WARN | `AC6_shipped_unchanged.png`, `AC6_one_support_warn.png` |
| **AC7. OrderCancelled cho order chưa có shipment hoàn tất không lỗi** | Cùng folder `05`: `AC7-01`, `AC7-02`, `AC7-03-E`, order fixture `106` | Không có shipment; consumer có processed marker `1` sau delivery; có cancellation tombstone; history/event/carrier call `0`. Đây là bằng chứng consumer đã xử lý xong, không chỉ là producer gửi được Kafka | `AC7_missing_shipment_processed.png` |
| **AC8. events.md flow 4 ghi quy tắc R1: PAID hủy tới ShipmentPickedUp, không có handover request** | Folder `06 AC8 R1 docs`: `AC8-01`, rồi mở [events.md — Integration flows](../../api/events.md#integration-flows-and-compatibility) | Kết quả contract chỉ ra đúng file/flow và quy tắc; ảnh nội dung flow 4 nêu `PAID`, `until ShipmentPickedUp`, `no handover request or request/ACK protocol` | `AC8_r1_contract_check.png`, `AC8_events_flow4.png` |

AC6 cần ảnh log hoặc trường evidence có nội dung WARN thực đọc từ file service; chỉ ảnh HTTP 200 của companion không chứng minh có WARN. AC5 cần phần `cancelCalls`/chi tiết request carrier từ companion; chỉ thấy DB `CANCELLED` chưa chứng minh đã gọi `CarrierAdapter`. AC1 cần history/event tương quan; chỉ thấy status mới ở GET chưa đủ.

## 6. Bảng mapping GHN được kiểm tra

Mỗi dòng là một shipment độc lập. Điều này tránh trường hợp hai GHN status cùng ánh xạ một target bị coi là no-op trong cùng shipment, làm thiếu evidence về mapping.

| Case | GHN status | Internal status | Event |
| --- | --- | --- | --- |
| `AC1-M01` | `ready_to_pick` | `CREATED` | `ShipmentStatusUpdated` |
| `AC1-M02` | `picking` | `PICKING` | `ShipmentStatusUpdated` |
| `AC1-M03` | `money_collect_picking` | `PICKING` | `ShipmentStatusUpdated` |
| `AC1-M04` | `picked` | `SHIPPED` | `ShipmentPickedUp` |
| `AC1-M05` | `storing` | `IN_TRANSIT` | `ShipmentInTransit` |
| `AC1-M06` | `sorting` | `IN_TRANSIT` | `ShipmentInTransit` |
| `AC1-M07` | `transporting` | `IN_TRANSIT` | `ShipmentInTransit` |
| `AC1-M08` | `delivering` | `OUT_FOR_DELIVERY` | `ShipmentStatusUpdated` |
| `AC1-M09` | `money_collect_delivering` | `OUT_FOR_DELIVERY` | `ShipmentStatusUpdated` |
| `AC1-M10` | `delivered` | `DELIVERED` | `ShipmentDelivered` |
| `AC1-M11` | `delivery_fail` | `DELIVERY_FAILED` | `ShipmentFailed` |
| `AC1-M12` | `exception` | `DELIVERY_FAILED` | `ShipmentFailed` |
| `AC1-M13` | `lost` | `DELIVERY_FAILED` | `ShipmentFailed` |
| `AC1-M14` | `damage` | `DELIVERY_FAILED` | `ShipmentFailed` |
| `AC1-M15` | `scrap` | `DELIVERY_FAILED` | `ShipmentFailed` |
| `AC1-M16` | `waiting_to_return` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M17` | `return` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M18` | `return_transporting` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M19` | `return_sorting` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M20` | `returning` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M21` | `return_fail` | `RETURNING` | `ShipmentStatusUpdated` |
| `AC1-M22` | `returned` | `RETURNED` | `ShipmentReturned` |
| `AC1-M23` | `cancel` | `CANCELLED` | `ShipmentCancelled` |

`ShipmentPickedUp` là tên event; status nội bộ là `SHIPPED`. Fixture của `AC1-M01` có trạng thái `PENDING_TRACKING` nhưng đã có trackingCode để callback tạo ra thay đổi `CREATED` thực sự; đây là fixture kiểm tra riêng mapping, không phải luồng tạo shipment #202.

## 7. Các testcase bổ sung cho OUTPUT và contract

| Phần | Nội dung kiểm tra | Evidence cần chụp |
| --- | --- | --- |
| Folder `07`, cập nhật thủ công | Admin và Support có role + privilege được PATCH; buyer bị 403; không có bearer bị 401; chuyển lùi bị 409 | Response `ApiResponse` cho ca 200, Problem Details có `SHIPMENT_*` cho lỗi; history source `MANUAL` và actorId của JWT |
| Folder `07`, override | Có reason thì thành công; thiếu/rỗng/whitespace reason bị 400; không dùng override để chuyển lùi | `SHIPMENT_VALIDATION_FAILED` cho dữ liệu sai; lý do và changedBy được lưu đúng cho ca thành công |
| Folder `07`, tracking | Chủ shipment đọc được; buyer khác nhận 404; phân trang có `data` dạng array và `page` cùng cấp; page/size không hợp lệ bị 400 | Body có `code`, `message`, `data`, `page.number/size/totalElements/totalPages`; ca owner/otherBuyer |
| Folder `07`, chưa có tracking | Manual progress bị 409 khi trackingCode null; không làm shipment thoát khỏi retry lấy tracking | Status vẫn `PENDING_TRACKING`, không có history/event mới |
| Folder `08 Tracking poller` | Không gửi webhook; arm fixture `109`, GHN mock trả `transporting`; job tự chuyển `SHIPPED → IN_TRANSIT` | Evidence history source `POLLER`, history/event `1`, mock nhận request detail; không dùng manual PATCH để thay job |
| Folder `09`, carrier từ chối hủy | Support gửi PATCH `CANCELLED` cho fixture `112`, GHN mock từ chối | 409 `SHIPMENT_CARRIER_CANCELLATION_REJECTED`; shipment không đổi, history/event/processed marker bằng 0, có lời gọi cancel thật tới mock; không đưa message lỗi vào Kafka |
| Folder `05`, chưa có booking | Fixture `113` được hủy local | Status `CANCELLED`, history/event `1`, không gọi carrier vì không có trackingCode |
| Contract API | Các operation có mặt trong OpenAPI và inventory | `AC8-01`/contract và kết quả `node docs/api/check-contract.mjs` |

## 8. Truy vấn SQL để chụp DB độc lập

Không cần tự chạy SQL để dùng collection: `start.ps1` và folder `00 Setup` đã nạp dữ liệu. Các lệnh dưới đây dành cho người muốn chụp thêm bảng DB độc lập, dùng container và database riêng do script chuẩn bị:

```powershell
Get-Content -Raw docs/testing/shipment-status-203/sql/02-evidence.sql | docker exec -i issue203-postgres psql -X -v ON_ERROR_STOP=1 -U issue203 -d shipment_db
```

Các file dữ liệu: [01-seed.sql](sql/01-seed.sql), [02-evidence.sql](sql/02-evidence.sql), [03-cleanup.sql](sql/03-cleanup.sql). Seed cho phép truyền `buyer_id` bằng cú pháp psql; collection tự truyền UUID `sub` của buyer token nên không cần nhập thủ công. Luôn dùng request seed khi chạy collection để reset đồng thời bộ đếm mock và log baseline. Chạy SQL evidence sau testcase, không chạy cleanup trước khi chụp.

Query tổng hợp đầu tiên của `02-evidence.sql` trả `evidence`, `ac`, `provider_status`, `expected_status`, `actual_status`, `actual_version`, `history_rows`, `event_rows`, `receipt_rows`, `published_event_rows`, `event_history_contract_ok`, `case_pass`. Query tiếp theo đối chiếu processed marker/tombstone cho từng ca hủy; phần `EVENT-HISTORY-DETAIL` hiển thị từng history/event để đối chiếu ID. Cột `case_pass` của `MANUAL-UPDATE` và `MANUAL-OVERRIDE` chỉ đạt sau khi bạn đã đăng nhập và chạy folder `07`; nếu chưa chạy thì hai dòng đó chưa đạt, không phải bằng chứng API đã lỗi.

Để cleanup sau khi đã lưu kết quả:

```powershell
./docs/testing/shipment-status-203/stop.ps1 -KeepContainers
Get-Content -Raw docs/testing/shipment-status-203/sql/03-cleanup.sql | docker exec -i issue203-postgres psql -X -v ON_ERROR_STOP=1 -U issue203 -d shipment_db
```

Outbox chứng minh một event đã được ghi cùng transaction; `published_at` chứng minh relay đã đánh dấu publish. Evidence của companion có `brokerEvents`, chứa event thực nhận từ topic `nexus.shipment.events` của broker riêng #203, gồm Kafka key, eventType, aggregateId, historyId và shipmentVersion. Kafka UI local trỏ broker `nexus-kafka` hiện có sẽ không tự thấy broker riêng `issue203-kafka`; dùng `brokerEvents` để chụp mà không phải cấu hình thêm UI. Kafka giao ít nhất một lần nên số message trên broker không thay thế tiêu chí một outbox fact; cùng eventId bị relay giao lại không phải một lần thay đổi nghiệp vụ mới.

## 9. Xử lý lỗi thường gặp

| Hiện tượng | Kiểm tra |
| --- | --- |
| Môi trường chưa báo `READY`, hoặc helper không kết nối được | Chạy lại `start.ps1`; kiểm tra Docker Desktop và file `evidence/service.stdout.log`, `evidence/companion.stderr.log` |
| Webhook hợp lệ nhận 401 hoặc 400 | Import lại collection mới; đừng giữ environment cũ có biến `webhookSecret` hoặc `shopId` ghi đè collection defaults |
| Webhook GHN báo unsupported carrier | Đang gọi nhầm service cũ: collection đúng dùng `http://localhost:18088` |
| Không thấy shipment fixture | Request seed chưa chạy, companion/service trỏ khác database, hoặc ID đã bị đổi |
| Protected request nhận 401 | Access token thiếu/hết hạn/sai issuer/audience; đăng nhập lại rồi cập nhật đúng biến token |
| Protected request nhận 403 | Đúng vai trò và privilege chưa có trong JWT; lấy lại token sau khi thay quyền |
| Buyer sở hữu nhận 404 | Điền buyer token rồi chạy lại folder `00 Setup`; không đổi buyer token giữa lượt chạy mà không seed lại |
| Publish Kafka thành công nhưng evidence chờ mãi | Kiểm tra `OrderCancelledConsumer` enabled, đúng topic/key, consumer log và Kafka bootstrap; đừng coi producer ACK là consumer đã xử lý |
| AC6 thiếu WARN | Chạy `start.ps1` và `00 Setup` trước; kiểm tra `evidence/shipment-service.log`, đối chiếu orderId/eventId trong `warningLines` |
| Số đếm history/event lớn hơn expected | Có chạy lại ca mutate mà chưa seed; poller chưa được park; companion bị restart giữa lượt chạy |
| Poller không thay đổi fixture109 | Đã chạy request arm, job enabled, GHN_BASE_URL trỏ companion, port9903 truy cập được; kiểm tra request detail và retry fields |

## Contract sau khi đồng bộ develop (#206)

GET /shipments/{id}/tracking giữ data dạng object tổng hợp để tương thích FE trên develop. Các request TRACK phân trang/audit dùng GET /shipments/{id}/history; TRACK-SUMMARY kiểm tra contract tổng hợp. Migration V4 bổ sung seller_id từ snapshot tin cậy. Không lấy ownership từ shipping_info do người dùng gửi.

## 10. Kết quả xác minh bộ testcase

Đã chạy Postman CLI ngày 11/11/2026 trên bộ Docker riêng: **101 request, 480 assertion, không có lỗi**. SQL evidence đạt cả **36 case** và **5 kiểm tra consumer**. SQL cleanup đã chạy thành công, sau đó dữ liệu được seed lại cho lượt chụp của bạn.

Lượt xác minh tự động dùng JWT RSA tạm để kiểm tra role, privilege, ownership và assertion; không đăng nhập hoặc thay đổi tài khoản Keycloak của bạn. Sau khi xác minh, service được chuyển lại issuer/JWKS Keycloak local thật và credential kiểm tra tạm được xóa. Bạn vẫn tự login và chạy Postman để tạo evidence của mình. GHN là provider mock local; service, database và Kafka đều thật.

Log lượt kiểm tra nằm ở `evidence/postman-final-validation.log`, kết quả SQL ở `evidence/sql-validation.txt`. Thư mục evidence được gitignore. Kết quả chi tiết: [verification.md](verification.md).
