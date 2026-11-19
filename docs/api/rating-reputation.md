# Ratings và reputation (#212, gồm #213 và #214)

Hợp đồng giao tiếp được duy trì trong [delivered OpenAPI](openapi/rating-service.yaml). Rating-service sở hữu toàn bộ rating, điểm uy tín, policy và lịch sử. Không gọi commerce để xác định quyền đánh giá: chỉ dùng projection `rating_eligibility` từ `OrderCompleted`.

## Package theo feature

```text
com.group5.ratingservice/
├── RatingServiceApplication.java
├── rating/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/
│   ├── event/
│   └── util/
├── reputation/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── model/
│   ├── enums/
│   ├── dto/
│   ├── event/
│   ├── policy/
│   ├── config/
│   └── backfill/
├── eligibility/
│   ├── controller/
│   ├── service/
│   ├── repository/
│   ├── entity/
│   ├── dto/
│   ├── enums/
│   ├── event/
│   └── security/
└── shared/
    └── exception/
```

`rating` sở hữu rating, API, repository, masked name và `UserRated`. `reputation` sở hữu aggregate, `TrustLevel`, policy, history, consumers tính điểm và backfill. `eligibility` sở hữu completed-order projection cùng consumer `OrderCompleted`, API REP-10 và machine authorization. Mỗi feature được chia package con theo trách nhiệm để dễ tìm lớp; cách chia này áp dụng theo yêu cầu cụ thể cho rating-service. `shared` chứa xử lý lỗi dùng chung; test helpers và migration tests nằm trong package `shared` của test source. Các test nghiệp vụ nằm theo feature và package tương ứng.

## Rating

Buyer và seller của một canonical completed order được đánh giá đối tác một lần cho mỗi bên. Đơn STANDARD và AUCTION đều dùng `orderId`; `auctionId` chỉ là context. Người gửi lấy từ JWT, đối tác lấy từ projection. Không có API sửa/xóa rating trong phạm vi này.

`POST /api/v1/ratings` lưu rating và `UserRated` vào outbox trong cùng transaction. DB unique bảo vệ cả các request đồng thời. Điểm phải là JSON integer 1–5; comment tối đa 1000 ký tự. Snapshot tên được mask trước khi lưu, dựa trên claim `name` đã xác thực: chữ đầu/cuối và `***`, thiếu hoặc ít hơn ba chữ dùng `***`. Danh sách không trả raw rater UUID, email hoặc tên đầy đủ.

`UserRated` không có comment/tên; envelope aggregateId là ratingId, Kafka key là toUserId. Consumer reputation xử lý bất đồng bộ, nên response POST không hứa điểm đã cập nhật ngay lập tức. Client đọc reputation sau khi sự kiện được xử lý; không tự tính điểm từ rating response.

Response có `ratedByMe`, được tính từ JWT của người đang đọc. Buyer tìm rating của đơn bằng `orderId` và `ratedByMe=true`, không cần lộ raw rater ID cho người khác. Client gửi `orderId`, `score`, `comment`; service tự xác định đối tác từ completed-order projection.

## Hạn chế hành động và khiếu nại đánh giá

Admin có `USER.REPUTATION.ADJUST` tạo hạn chế qua `POST /api/v1/reputations/{userId}/restrictions` với `action` (`BID` hoặc `SELL`), `reason` tối đa 1000 ký tự và `restrictedUntil` trong tương lai. `DELETE /api/v1/reputations/{userId}/restrictions/{id}?reason=...` gỡ hạn chế, giữ người thao tác, thời gian và lý do. Admin có `USER.REPUTATION.VIEW` xem danh sách có phân trang; buyer/seller có `REPUTATION.VIEW` chỉ xem hạn chế của chính mình. Hạn chế đã hết hạn hoặc được gỡ tự ngừng áp dụng. Nhiều hạn chế đang hiệu lực của cùng hành động lấy thời điểm hết hạn muộn nhất.

Internal eligibility trả `restrictedUntil` và từ chối khi hạn chế còn hiệu lực dù điểm đủ ngưỡng. Auction mặc định bật enforcement; lỗi `AUCTION_REPUTATION_RESTRICTED` (403) trả lý do, hành động và thời điểm được phép thử lại. Cấu hình machine token cho auction phải hoàn tất trước khi chạy luồng bid hoặc tạo auction.

Người nhận đánh giá có `REPUTATION.RATE` tạo khiếu nại bằng `POST /api/v1/ratings/{id}/disputes` với `reason`. Mỗi rating có một khiếu nại; người viết rating và người ngoài không được khiếu nại thay. Admin đọc `GET /api/v1/ratings/disputes` bằng `USER.REPUTATION.VIEW`, hỗ trợ `status`, `page`, `size`; quyết định bằng `PATCH /api/v1/ratings/disputes/{id}` với `status` (`APPROVED`/`REJECTED`) và `reason`, cần `USER.REPUTATION.ADJUST`.

Chấp nhận khiếu nại ẩn rating khỏi danh sách thông thường và loại đúng đóng góp đã áp dụng khỏi `ratingCount`/`ratingScoreSum`, tính lại điểm và trust level trong cùng transaction. Aggregate lock đồng bộ việc duyệt với consumer: `UserRated` đến muộn, lặp hoặc backfill không khôi phục rating đã ẩn. Từ chối giữ nguyên rating. Response rating có `disputeStatus` để client tránh hiện lại nút gửi khiếu nại. Quyết định cuối cùng không sửa lại; lặp đúng actor, trạng thái và lý do trả kết quả đã lưu. Bảng `reputation_moderation_audit` giữ mọi thao tác tạo/gỡ hạn chế và gửi/duyệt khiếu nại; duyệt được chấp nhận còn có `RATING_DISPUTE_APPROVED` trong lịch sử reputation.

## Policy v1

Đây là các mặc định triển khai **có cấu hình**, không phải công thức số học được SRS cung cấp. Config Server khai báo tại `config/rating-service.yml`, prefix `nexus.reputation`.

| Thuộc tính | Mặc định |
| --- | --- |
| version | `reputation-policy-1` |
| rating-weight | 30 |
| success-weight | 20 |
| minimum-to-bid | 40, ghi đè bằng `MIN_REPUTATION_TO_BID` |
| minimum-to-sell | 50, ghi đè bằng `MIN_REPUTATION_TO_SELL` |
| timeout-penalty | 10, ghi đè bằng `REPUTATION_TIMEOUT_PENALTY` |
| basic-max | 59 |
| trusted-max | 79 |

Giá trị raw base:

```text
50
+ ratingWeight × (averageRating − 3) / 2       nếu ratingCount > 0
+ successWeight × successful / total         nếu total > 0
− penaltyPoints
```

Điểm hiển thị là `clamp(roundHalfUp(rawBase + adjustmentOffset), 0, 100)`. Phép chia dùng decimal scale 12. Lịch sử ghi version, các tham số thực tế, inputs trước/sau và delta sau clamp để giải thích kết quả.

Người chưa có hoạt động giữ `50/NEW`. Khi có hoạt động: BASIC 0–59, TRUSTED 60–79, TOP 80–100 theo hai biên cấu hình. Version và các biên bắt buộc; cấu hình không hợp lệ ngăn service khởi động. Biên BID/SELL so sánh `score >= minimum`, không dựa vào tên trust level.

Ví dụ khi chưa có adjustment/penalty:

| Inputs | Score / trust |
| --- | --- |
| Chưa có hoạt động | 50 / NEW |
| Một completed order, chưa nhận rating | 70 / TRUSTED |
| Một completed order, nhận rating 5 | 100 / TOP |
| Một completed order, nhận rating 1 | 40 / BASIC |
| Chỉ một payment timeout | 40 / BASIC |

`OrderCompleted` tăng total/success cho cả buyer và seller đúng một lần. AUCTION completed order cũng tăng successful auction count nhưng không tạo thêm một canonical transaction. Timeout tăng total/failed và failed auction count chỉ cho winner; không phạt seller hay losing bidders.

Timeout hợp lệ phải giảm điểm hiển thị ít nhất penalty đã cấu hình, trừ giới hạn score=0. Nếu raw score dương vượt trần che mất penalty, engine điều chỉnh offset có ghi history để đảm bảo `newScore <= max(0, previousScore − penalty)`. Đây là quy tắc saturation của policy v1; tránh trường hợp score=100 vẫn giữ nguyên sau một late-payment penalty.

Admin adjustment dùng delta số nguyên và reason bắt buộc. Kết quả là chính xác `currentScore + delta`; ngoài 0–100 bị từ chối, không clamp. Offset được tính từ raw base để giữ tác dụng qua các sự kiện sau, kể cả khi điểm trước đó chạm trần/sàn. `Idempotency-Key` tùy chọn, scope theo target user; cùng key phải cùng actor/delta/reason đã strip. Replay trả kết quả gốc 200; request mới 201; nội dung khác 409.

## Idempotency và lịch sử

`IdempotentConsumer` bảo vệ envelope; local source/effect/outcome ledgers bảo vệ nghiệp vụ. Một eventId mới, source revision mới hoặc policy version mới không được cộng lại cùng completion/rating/timeout. Snapshot mâu thuẫn rollback và phải được đối soát. Hai bên completion được lock theo thứ tự cố định; cập nhật inputs, aggregate, history, outbox và processed marker cùng transaction.

`ReputationScoreUpdated` là kết quả, không phải input tính lại. `ReputationPenaltyApplied` chỉ được phát khi penalty thực sự được áp dụng. `TrustLevelChanged` chỉ phát khi level đổi. History giữ cả thay đổi có delta=0, bao gồm counters hoặc trust thay đổi.

Mỗi reputation consumer mở tracing scope quanh transaction và đóng scope trong `finally`. Điều này cho phép `EventEnvelopeFactory` tạo event kết quả ngay cả khi Kafka listener chưa bật observation hoặc không có span đầu vào. Lỗi xử lý vẫn rollback transaction và khôi phục trace context của thread; test dùng tracer/factory thật kiểm tra trường hợp này.

## Backfill và dữ liệu lịch sử

Consumer projection cũ đã có processed markers, nên reputation dùng group/consumer ID riêng với earliest retained offset. Không xóa marker để ép xử lý lại và không phát event giả.

Backfill mặc định tắt. Trước rollout, bật dry run để xem candidates, unverified ratings/aggregates và cursor trong log:

```text
--nexus.reputation.backfill.enabled=true
--nexus.reputation.backfill.dry-run=true
--nexus.reputation.backfill.batch-size=100
```

Sau khi đối soát nguồn, đổi `dry-run=false`. Có thể tiếp tục từ `--reputation-completion-cursor=<UUID>` và `--reputation-rating-cursor=<UUID>` đã ghi trong batch report. Chạy lại từ đầu là an toàn vì batch và live consumer dùng cùng semantic keys. Rating legacy thiếu completion evidence được báo unverified; không tự chế completed order. Timeout lịch sử phải replay từ event đáng tin nếu còn retention hoặc đối soát riêng với auction owner.

Aggregate legacy chưa có policy version mà score/trust không phải `50/NEW`, hoặc có counters/inputs/adjustment khác 0, bị chặn cập nhật và eligibility bằng 503 `RATING_RECONCILIATION_REQUIRED`. Public GET vẫn hiển thị score lưu sẵn. Admin adjustment không được dùng để vượt qua bước đối soát. Không tự động suy diễn các nguồn đã tạo điểm/counters lịch sử; phải lập bản đối soát từ evidence trước khi chuyển trạng thái dữ liệu.

Ghi Kafka high-watermark trước backfill, chờ live consumer bắt kịp mốc đó và đối soát source/effect counts sau batch. Nếu retention đã mất event, batch projection chỉ bù được completion/rating có bằng chứng cục bộ; không tuyên bố đã khôi phục mọi penalty. Chỉ bật auction enforcement sau đối soát, không chỉ dựa vào việc batch đã kết thúc.

## Eligibility nội bộ

REP-10 nhận action `BID` hoặc `SELL`, trả quyết định 200 với score, trust, minimum, reason và policyVersion. Caller phải kiểm tra đúng user/action và envelope; malformed response hoặc upstream lỗi phải fail closed.

Machine authentication và cách provision client được mô tả tại [rating-eligibility.md](../security-keycloak/rating-eligibility.md). Gateway không route internal; service vẫn kiểm tra machine identity khi gọi trực tiếp. Role Admin không tự cấp quyền gọi REP-10.

## Auction enforcement

`FEATURE_REPUTATION_ENFORCEMENT` mặc định `true`. ON kiểm tra ngưỡng điểm và hạn chế theo thời hạn trong rating-service: SELL cho seller tạo auction, BID cho bidder tạo bid mới. Cần chạy rating-service và cấu hình machine client trước khi ghi auction/bid. OFF là cấu hình bỏ qua enforcement cho môi trường phát triển có chủ đích; không gọi rating-service hoặc Keycloak. Giữ điểm/threshold và restriction logic trong rating-service.

Bid đã accepted có cùng key/auction/bidder/amount được replay 200 trước khi gọi eligibility. Bước đọc key trước đó có read transaction ngắn để giải phóng connection; auction tắt Open-in-View trong cấu hình service. Kiểm tra qua mạng nằm ngoài transaction ghi; khi vào transaction, key được đọc lại trước conditional UPDATE để giữ concurrency/idempotency. Nếu một request khác đã commit cùng key trong lúc eligibility của request này thất bại, đọc lại và replay kết quả đúng hoặc báo conflict cho payload khác. Transaction ghi bắt đầu sau network check để không dùng thời điểm PostgreSQL cũ chấp nhận bid quá hạn.

Quyết định deny hợp lệ trả 403 `AUCTION_REPUTATION_TOO_LOW`, reason trong cả `detail` và `reason`. Timeout, service/token failure, circuit open, non-200 hoặc sai envelope/user/action/kiểu dữ liệu trả 503 `AUCTION_REPUTATION_UNAVAILABLE`. Không fallback allow, không relay token người dùng, không retry ngầm. Response phải có boolean decision nhất quán với score/minimum 0–100, trust enum hợp lệ và policy version/reason không rỗng.

Feign dùng connect timeout 500ms, read timeout 1500ms; token timeout mặc định 1500ms; circuit breaker `auctionReputation` có time limiter 3s. Token được cache theo expiry với khoảng refresh an toàn; 401/403 từ rating invalidates cache cho lần gọi sau, không tự retry thao tác hiện tại. Các giá trị thuộc cấu hình service, có thể ghi đè theo môi trường.

Khi bị từ chối hoặc lỗi, không thay auction price/leader/count/version/deadline, không thêm bid/outbox và không tiêu thụ idempotency key. Có thể retry cùng key sau khi hệ thống phục hồi. Test auction dùng fake eligibility client cho rule/DB assertions và Feign thật qua local HTTP server cho transport/auth/cache/circuit behavior; không cần chạy rating-service để test auction.
