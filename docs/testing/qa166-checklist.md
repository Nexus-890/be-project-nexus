# QA #166 — các ST/AC có thể tick

Đối chiếu ngày22/11/2026. Chỉ đánh dấu các ô dưới đây sau khi commit QA và proof
coverage đã xuất hiện trong MR!197. Hướng dẫn này không tự chỉnh checklist issue.

Bảng20module gồm19module integration local có thay đổi sẵn và module receipt acceptance chạy thêm trên source publish.
Kiểm chứng trên source publish được ghi riêng trong `test-report.md`; không suy
rộng thành fullsuite của commit publish hoặc thành STAGING acceptance.

## Subtasks

| Mã | Tick | Nội dung gốc | Bằng chứng / điều kiện |
|---|---|---|---|
| ST166.1 | ✅ | Add the JaCoCo plugin to lib/pom.xml with a 70 percent line rule bound to verify | POM: BUNDLE / LINE / COVEREDRATIO0.70, phaseverify |
| ST166.2 | ✅ | Run every module's test suite and record the pass count and the coverage percentage | Matrix20module local, JUnit/JaCoCo; Inventory IT chạy riêng10/10; User/Payment không có IT khác bị bỏ lại |
| ST166.3 | ✅ | Record which modules fail the rule | Discovery/common-core/common-web/common-test được nêu rõ |
| ST166.4 | ✅ | Open an issue for every module below 70 percent, or fix it | #273, #274, #275, #276 đang mở |
| ST167.1 | ✅ | Run the 100-reservation and 50-bid tests ten times each and record the results |10+10repetitions, mỗi test không fail/error/skip; bản publish cũng đã chạy lại10+10 |
| ST167.2 | ⬜ | Walk one purchase and one auction end to end on STAGING and record the ids | Chưa có STAGING, luồng local còn bị chặn; #162/#281 |
| ST167.3 | ✅ | Open an issue for every failure, and list the issue numbers in the report | #273–282; lỗi local đã sửa gắn #166, môi trường gắn #166/#162 |
| ST167.4 | ⬜ | Re-run everything once the open issues are closed | Các issue vẫn đang mở; chưa có final rerun sau khi sửa hết |
| ST174.1 | ✅ | Kill commerce-service after the order is written but before OrderCreated publishes, then restart it | JVM bị forcekill sau commit + pendingoutbox +0brokerrecords; restart tớiSETTLED |
| ST174.2 | ✅ | Deliver AuctionWon three times | Cùng envelope gửi qua broker3lần, đợi committedoffset rồi đếm1order/1event |
| ST174.3 | ✅ | Stop auction-service while OrderCreated is published, then start it | Có brokerrecord khi Auction dừng; restart consumer đạtSETTLED |
| ST174.4 | ✅ | Record each scenario and its result in the test report |3JSONscenario, auction/orderIDs, SQLcounts và trạng thái trong report |

## Acceptance criteria

| Mã | Tick | Nội dung gốc | Bằng chứng / điều kiện |
|---|---|---|---|
| AC166.1 | ✅ | docs/test-report.md lists every module with its pass count and coverage percentage | Matrix20module; parentaggregator/test-onlymodule ghiN/A có lý do |
| AC166.2 | ✅ | Every module below 70 percent is named, with either a merged fix or an open issue number |4module tương ứng #273–276; tiêu chí cho phép issue còn mở |
| AC166.3 | ✅ | ./mvnw verify fails on a module below the rule, shown once in the merge request | Discovery test1/1pass, LINE1/3<0.70 rồiBUILDFAILURE; log/proof có trong mô tả MR |
| AC166.4 | ✅ | No test is disabled to make the number | Diff QA không thêm @Disabled/exclusions;8skip lịch sử được giữ nguyên và báo cáo |
| AC167.1 | ✅ | The 100-reservation test is green on all 10 runs, and the 50-bid test is green on all 10 runs | Concurrencysummary và20markers, exit0 |
| AC167.2 | ⬜ | One purchase and one auction have been walked end to end on STAGING, with their order and auction ids in the report | Các ID recovery thuộc DB disposable, không thay cho STAGING IDs |
| AC167.3 | ✅ | Every failure in the report has an issue number, and none is described only in prose | Mọi dòng knownbroken có số issue hoặc #166 là issue chứa fix |
| AC167.4 | ⬜ | The final run has no open High or Critical failure | Chưa có finalrun sau sửa hết; issue chưa triage severity, không suy đoán là hếtHigh/Critical |
| AC174.1 | ✅ | Killing commerce-service after the order is written but before OrderCreated publishes, then restarting, leaves exactly one order and still reaches SETTLED | Scenario commerce-crash-before-publish |
| AC174.2 | ✅ | AuctionWon delivered three times leaves one order and one OrderCreated | Scenario auction-won-delivered-three-times, brokercount độc lập |
| AC174.3 | ✅ | With auction-service down during OrderCreated, the auction reaches SETTLED after it starts | Scenario auction-offline-during-order-created |
| AC174.4 | ✅ | SELECT count(*) FROM orders WHERE reference_id = '<auction>' is 1 after every scenario |3JSON đều orderCount1 và auctionorderId đúng |

Tổng: **ST10/12, AC10/12 —20/24ô**. Giữ #166 mở và MR Draft vì4ô còn thiếu.

## Có cần chụp code hoặc màn hình?

Không bắt buộc chụp sourcecode: reviewer có diff và link file trong MR.
Các JSON/CSV/log text được commit là bằng chứng chính. Nếu cần ảnh cho checklist,
chụp đúng các phần sau, giữ tên test/lệnh và kết quả trên cùng ảnh:

1. JaCoCo: lệnhverify, Discovery1/1testpass, rule0.33<0.70, BUILDFAILURE.
2. Concurrency: summary10/10Reservation và10/10Bid, failures/errors/skips0.
3. Recovery:3/3PASS và bảng3scenario, mỗi dòng orderCount1/SETTLED cùngauction/orderID.
4. Matrixcoverage20module và số issue tương ứng có thể chụp thêm nếu reviewer cần.

Ảnh localcheckout đã có: `evidence/qa166/runtime/checkout-live.png`. Ảnh này cho
thấy blocker carrier/voucher và chỉ là local evidence. Không dùng để tickSTAGING.
Không chụp .env, token, requestauthorizationheader hoặc credential. Khi cóSTAGING,
cần bằng chứng luồng purchase +auction hoàn tất, URLmôi trường và cácIDthực tế.
