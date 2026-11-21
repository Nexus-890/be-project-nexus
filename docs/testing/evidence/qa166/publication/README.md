# Publication-source verification — 22 November 2026

Base: origin/develop `1ea5801e07de79eb4d007f85a7799a79bcc3029e` plus this MR QA changes. These checks are independent of the earlier integration baseline.

- ReservationConcurrencyIT: 10/10 pass, 100 requests per race.
- BidConcurrencyIT: 10/10 pass, 50 requests per race. The real reputation guard is explicitly enabled; EligibilityClient is a valid local decision fixture, verified to be called for the requests. HTTP/JWT/DB/queue invariants remain enabled.
- AuctionSettlementRecoveryIT: 3/3 pass; production processes, Kafka and two isolated databases.
- EnumMigrationTest: 2/2 pass, including seller default and invalid enum rejection.
- Discovery clean verify: one passing test, then expected coverage failure LINE1/3 (0.33) below0.70.

An initial bid run failed10/10 with AUCTION_REPUTATION_UNAVAILABLE after develop made enforcement default-on. The fixture was corrected under #166 and the complete10 repetitions rerun; no assertion or enforcement was disabled. Failed raw output remains local, because its diagnostic objects include ephemeral signed test JWTs. Only sanitized markers/counts are committed.

The harness initially lost target junction destinations during Maven clean, and its first receipt acceptance invocation lost the caller Maven cache setting. Both setup problems were corrected before the final checks. The quality runner now preserves caller MAVEN_OPTS. These are not application assertions or hidden green reruns.

No claim of full-suite execution for all current services or STAGING is made by this focused validation. Test-only receipt acceptance is recorded separately.

Receipt-completion-acceptance:11 cases,7passed,3fixture errors,1existing sandbox skip, verify exit1; [#282](https://git.fsoft-academy.edu.vn/hcm26_cpl_java_11/hcm26_cpl_java_11_group_5/-/work_items/282). This additional current-source module was not present in the earlier19-module checkout.
