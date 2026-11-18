package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCancelled;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payment.service.RefundService;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.request.ManualPayoutFilter;
import com.group5.paymentservice.payout.dto.response.ManualCommandResult;
import com.group5.paymentservice.payout.dto.response.ManualPayoutView;
import com.group5.paymentservice.payout.enums.PayoutReceiptStatus;
import com.group5.paymentservice.payout.enums.PayoutStatus;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.repository.jdbc.JdbcManualPayoutRepository;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real PostgreSQL transactions/locks/accounting; evidence storage is an explicitly isolated test
 * double.
 */
@TestPropertySource(
        properties = {
            "nexus.payout.execution-mode=MANUAL",
            "nexus.payout.manual.enabled=true",
            "nexus.payout.jobs-enabled=false",
            "nexus.payout.manual.source-accounts[0].id=platform-main",
            "nexus.payout.manual.source-accounts[0].alias=Platform test source",
            "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class ManualPayoutFlowTest extends PaymentDatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ManualPayoutService manual;
    @Autowired ManualPayoutQueryService queries;
    @Autowired PayoutEligibilityService completions;
    @Autowired RefundService refunds;
    @Autowired BankAccountService banks;
    @Autowired ManualPayoutProperties settings;
    @Autowired PayoutProperties payoutSettings;
    @MockitoBean PayoutEvidenceStorage storage;
    @MockitoBean PayoutGateway gateway;
    @MockitoSpyBean OutboxWriter outboxWriter;
    @MockitoSpyBean JdbcManualPayoutRepository manualRepository;
    private final CurrentUser admin = actor("ADMIN");
    private final CurrentUser support = actor("SUPPORT_STAFF");

    @BeforeEach
    void resetSettings() {
        settings.setEnabled(true);
        settings.setQrDeadline(Duration.ofMinutes(10));
        settings.setClaimLease(Duration.ofMinutes(5));
        settings.setSourceAccounts(
                List.of(
                        new ManualPayoutProperties.SourceAccount(
                                "platform-main", "Platform test source")));
        payoutSettings.setExecutionMode("MANUAL");
        payoutSettings.setJobsEnabled(false);
        when(storage.configured()).thenReturn(true);
    }

    @Test
    void aMissingPlatformSourceAliasBlocksClaimUntilExplicitReadinessRecovery() {
        Fixture f = funded();
        var before = detail(f);
        settings.setSourceAccounts(List.of());
        try {
            var notReady = detail(f);
            assertThat(notReady.allowedActions()).doesNotContain("CLAIM");
            var held = command(support, f, null, "CLAIM", new ClaimCommand(before.version()));
            assertThat(held.conflict()).isTrue();
            assertThat(held.result().status()).isEqualTo("ON_HOLD");
            assertThat(held.result().blockedReason()).isEqualTo("SOURCE_ACCOUNT_NOT_CONFIGURED");
            assertThat(held.result().currentAttempt()).isNull();
        } finally {
            settings.setSourceAccounts(
                    List.of(
                            new ManualPayoutProperties.SourceAccount(
                                    "platform-main", "Platform test source")));
        }
        var ready =
                command(
                                support,
                                f,
                                null,
                                "READINESS_CHECK",
                                new PayoutVersionCommand(detail(f).version(), null))
                        .result();
        assertThat(ready.status()).isEqualTo("PENDING");
        assertThat(ready.allowedActions()).contains("CLAIM");
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void preQrLeaseExpiryReleasesOnlyTheUnissuedClaimAndPostsNoMoney() {
        Fixture f = funded();
        settings.setClaimLease(Duration.ofNanos(1));
        ManualPayoutView claimed;
        try {
            claimed = claim(f, support);
        } finally {
            settings.setClaimLease(Duration.ofMinutes(5));
        }
        manual.expire(f.id());
        var released = detail(f);
        assertThat(released.status()).isEqualTo("PENDING");
        assertThat(released.currentAttempt().state()).isEqualTo("NOT_SENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qr_issued_at IS NULL FROM manual_payout_attempts WHERE"
                                        + " id=?",
                                Boolean.class,
                                claimed.currentAttempt().id()))
                .isTrue();
        assertThatThrownBy(() -> issue(f, support, claimed)).isInstanceOf(DomainException.class);
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void aBankChangeBeforeQrIssuanceReleasesTheStaleClaimWithoutAnInstruction() {
        Fixture f = funded();
        var claimed = claim(f, support);
        UUID bank =
                jdbc.queryForObject(
                        "SELECT seller_bank_id FROM payouts WHERE id=?", UUID.class, f.id());
        banks.update(
                f.seller(),
                bank,
                new BankAccountRequest("970436", "888888881234", "TEST SELLER", true, true));
        var result =
                command(
                        support,
                        f,
                        claimed.currentAttempt().id(),
                        "ISSUE_QR",
                        new AttemptVersionCommand(claimed.currentAttempt().version(), null));
        assertThat(result.conflict()).isTrue();
        assertThat(result.result().status()).isEqualTo("ON_HOLD");
        assertThat(result.result().currentAttempt().state()).isEqualTo("NOT_SENT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT qr_issued_at IS NULL FROM manual_payout_attempts WHERE"
                                        + " id=?",
                                Boolean.class,
                                claimed.currentAttempt().id()))
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void invalidConfirmationBusinessFactsLeaveTheIssuedAttemptAndLedgerUnchanged() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        var valid = confirmation(ready, "FT" + key());
        var before = businessSnapshot(f.seller().id());
        var invalid =
                List.of(
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                "not-approved",
                                valid.amount(),
                                valid.bankTransferReference(),
                                valid.transferredAt(),
                                valid.evidenceIds()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                "1.00",
                                valid.bankTransferReference(),
                                valid.transferredAt(),
                                valid.evidenceIds()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                valid.amount(),
                                "bad reference with spaces",
                                valid.transferredAt(),
                                valid.evidenceIds()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                valid.amount(),
                                valid.bankTransferReference(),
                                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
                                valid.evidenceIds()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                valid.amount(),
                                valid.bankTransferReference(),
                                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                                valid.evidenceIds()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                valid.amount(),
                                valid.bankTransferReference(),
                                valid.transferredAt(),
                                List.of()),
                        new ConfirmationCommand(
                                valid.expectedAttemptVersion(),
                                valid.sourceAccountId(),
                                valid.amount(),
                                valid.bankTransferReference(),
                                valid.transferredAt(),
                                List.of(UUID.randomUUID())));
        for (var command : invalid) {
            assertThatThrownBy(
                            () ->
                                    command(
                                            support,
                                            f,
                                            ready.currentAttempt().id(),
                                            "CONFIRM_TRANSFER",
                                            command))
                    .isInstanceOf(DomainException.class);
            assertThat(businessSnapshot(f.seller().id())).isEqualTo(before);
        }
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void simultaneousConfirmationsWithDifferentKeysProduceExactlyOnePostingAndOneReplayableResult()
            throws Exception {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        var body = confirmation(ready, "FT" + key());
        String firstKey = key(), secondKey = key();
        CountDownLatch start = new CountDownLatch(1);
        List<ClaimOutcome> outcomes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () ->
                                    executeAfter(
                                            start,
                                            support,
                                            f.id(),
                                            ready.currentAttempt().id(),
                                            "CONFIRM_TRANSFER",
                                            firstKey,
                                            body));
            var two =
                    pool.submit(
                            () ->
                                    executeAfter(
                                            start,
                                            support,
                                            f.id(),
                                            ready.currentAttempt().id(),
                                            "CONFIRM_TRANSFER",
                                            secondKey,
                                            body));
            start.countDown();
            outcomes = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        var winner =
                outcomes.stream()
                        .filter(outcome -> outcome.result() != null)
                        .findFirst()
                        .orElseThrow();
        assertThat(outcomes.stream().filter(outcome -> outcome.result() != null)).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> outcome.error() != null)).hasSize(1);
        assertThat(
                        manual.execute(
                                        support,
                                        f.id(),
                                        ready.currentAttempt().id(),
                                        null,
                                        "CONFIRM_TRANSFER",
                                        winner.key(),
                                        body)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(outbox(f, "PayoutSucceeded")).isEqualTo(1);
    }

    @Test
    void adminProvenNotSentRequiresExplicitRetryAndNeverReusesTheOldQr() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        command(
                support,
                f,
                ready.currentAttempt().id(),
                "REPORT_PROBLEM",
                new ProblemCommand(
                        ready.currentAttempt().version(),
                        "Transfer was not submitted",
                        ready.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        var unknown = detail(f);
        var review = unknown.cases().getFirst();
        var resolution =
                new ResolutionCommand(
                        unknown.version(),
                        unknown.currentAttempt().version(),
                        review.version(),
                        review.id(),
                        ResolutionOutcome.CONFIRMED_NOT_SENT,
                        "Admin verified no execution",
                        unknown.evidence().stream().map(ManualPayoutView.Evidence::id).toList(),
                        null,
                        null,
                        null,
                        null);
        var failed =
                command(admin, f, unknown.currentAttempt().id(), "RESOLVE", resolution).result();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.currentAttempt().state()).isEqualTo("NOT_SENT");
        assertThat(movements(f, "PAYOUT")).isZero();
        var failedHistory = financialHistory(f, "FAILED");
        assertThat(failedHistory.get("from_status").toString()).isEqualTo("UNKNOWN");
        assertThat(failedHistory.get("to_status").toString()).isEqualTo("FAILED");
        assertThat(failedHistory.get("transaction_id")).isNull();
        assertThatThrownBy(() -> claim(f, support)).isInstanceOf(DomainException.class);
        command(
                admin,
                f,
                null,
                "RETRY",
                new PayoutVersionCommand(failed.version(), "Non-execution established"));
        var next = claim(f, support);
        assertThat(next.currentAttempt().attemptNo()).isEqualTo(2);
        assertThat(next.currentAttempt().reference())
                .isNotEqualTo(unknown.currentAttempt().reference());
    }

    @Test
    void adminConfirmedSentSettlesAnUnknownAttemptExactlyOnce() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        command(
                support,
                f,
                ready.currentAttempt().id(),
                "REPORT_PROBLEM",
                new ProblemCommand(
                        ready.currentAttempt().version(),
                        "Lost banking response",
                        ready.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        var unknown = detail(f);
        var review = unknown.cases().getFirst();
        String requestKey = key();
        var resolution =
                new ResolutionCommand(
                        unknown.version(),
                        unknown.currentAttempt().version(),
                        review.version(),
                        review.id(),
                        ResolutionOutcome.CONFIRMED_SENT,
                        "Bank proof confirms transfer",
                        unknown.evidence().stream().map(ManualPayoutView.Evidence::id).toList(),
                        "platform-main",
                        unknown.netAmount(),
                        "FT" + key(),
                        OffsetDateTime.now(ZoneOffset.UTC));
        var result =
                manual.execute(
                                admin,
                                f.id(),
                                unknown.currentAttempt().id(),
                                null,
                                "RESOLVE",
                                requestKey,
                                resolution)
                        .result();
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.currentAttempt().receiptStatus()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(
                        manual.execute(
                                        admin,
                                        f.id(),
                                        unknown.currentAttempt().id(),
                                        null,
                                        "RESOLVE",
                                        requestKey,
                                        resolution)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(outbox(f, "PayoutSucceeded")).isEqualTo(1);
        assertFinancialHistory(f, "MANUAL_CONFIRMED", "UNKNOWN", "SUCCEEDED", "PAYOUT");
    }

    @Test
    void activeSellerDisputeIsReusedAndEvidenceReviewNeverForgesSellerReceipt() {
        Fixture f = funded();
        var settled = settle(f, support);
        var first =
                command(
                                f.seller(),
                                f,
                                settled.currentAttempt().id(),
                                "SELLER_DISPUTE",
                                new DisputeCommand(
                                        settled.currentAttempt().receiptVersion(),
                                        DisputeReason.NOT_RECEIVED,
                                        "No credit yet"))
                        .result();
        command(
                f.seller(),
                f,
                settled.currentAttempt().id(),
                "SELLER_DISPUTE",
                new DisputeCommand(
                        first.currentAttempt().receiptVersion(),
                        DisputeReason.NOT_RECEIVED,
                        "Following up"));
        var current = detail(f);
        assertThat(current.cases()).hasSize(1);
        var review = current.cases().getFirst();
        manual.execute(
                admin,
                f.id(),
                current.currentAttempt().id(),
                review.id(),
                "REVIEW_DISPUTE",
                key(),
                new ReviewCommand(
                        current.currentAttempt().version(),
                        review.version(),
                        ReviewAction.TRANSFER_EVIDENCE_PROVIDED,
                        "Transfer proof available",
                        current.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        current = detail(f);
        assertThat(current.cases().getFirst().status()).isEqualTo("WAITING_SELLER");
        assertThat(current.currentAttempt().receiptStatus()).isEqualTo("DISPUTED");
        command(
                f.seller(),
                f,
                current.currentAttempt().id(),
                "SELLER_RECEIPT",
                new ReceiptCommand(current.currentAttempt().receiptVersion()));
        current = detail(f);
        assertThat(current.cases().getFirst().status()).isEqualTo("RESOLVED");
        var reopened =
                command(
                                f.seller(),
                                f,
                                current.currentAttempt().id(),
                                "SELLER_DISPUTE",
                                new DisputeCommand(
                                        current.currentAttempt().receiptVersion(),
                                        DisputeReason.CONFIRMATION_MISTAKE,
                                        "I confirmed receipt by mistake"))
                        .result();
        assertThat(detail(f).cases()).hasSize(2);
        assertThat(detail(f).cases().stream().filter(c -> !"RESOLVED".equals(c.status())))
                .hasSize(1);
        assertThat(reopened.currentAttempt().receiptStatus()).isEqualTo("DISPUTED");
        assertThat(reopened.currentAttempt().sellerConfirmedAt()).isNotNull();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
    }

    @Test
    void cancellationAndQrIssuanceRaceNeverAuthorizeBothRefundAndSellerPayment() throws Exception {
        Fixture f = funded();
        var claimed = claim(f, support);
        UUID buyer =
                jdbc.queryForObject(
                        "SELECT buyer_id FROM payment_sessions WHERE id=?",
                        UUID.class,
                        f.sessionId());
        UUID group =
                jdbc.queryForObject(
                        "SELECT order_group_id FROM payment_sessions WHERE id=?",
                        UUID.class,
                        f.sessionId());
        Instant time = Instant.now();
        var event =
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCancelled",
                        f.orderId().toString(),
                        time,
                        "manual-refund-race",
                        new OrderCancelled(
                                f.orderId(),
                                buyer,
                                "NX-" + f.orderId(),
                                "STANDARD",
                                null,
                                time,
                                "Order cancellation requires reconciliation",
                                group,
                                f.seller().id(),
                                5L,
                                "100000.00",
                                "VND",
                                admin.id()));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancellation =
                    pool.submit(
                            () -> {
                                start.await();
                                refunds.cancelled(event);
                                return true;
                            });
            var issuance =
                    pool.submit(
                            () ->
                                    executeAfter(
                                            start,
                                            support,
                                            f.id(),
                                            claimed.currentAttempt().id(),
                                            "ISSUE_QR",
                                            key(),
                                            new AttemptVersionCommand(
                                                    claimed.currentAttempt().version(), null)));
            start.countDown();
            cancellation.get(20, TimeUnit.SECONDS);
            issuance.get(20, TimeUnit.SECONDS);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refunds WHERE order_id=?",
                                Long.class,
                                f.orderId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_pending_order_cancellations WHERE"
                                        + " order_id=?",
                                Long.class,
                                f.orderId()))
                .isEqualTo(1);
        var result = detail(f);
        assertThat(result.currentAttempt().state()).isIn("QR_ISSUED", "NOT_SENT");
        if ("QR_ISSUED".equals(result.currentAttempt().state())) {
            var ready = evidence(f, support, result);
            var rejected =
                    command(
                            support,
                            f,
                            ready.currentAttempt().id(),
                            "CONFIRM_TRANSFER",
                            confirmation(ready, "FT" + key()));
            assertThat(rejected.conflict()).isTrue();
            assertThat(detail(f).status()).isEqualTo("UNKNOWN");
        } else assertThat(result.status()).isEqualTo("ON_HOLD");
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void queueCombinesStatusOrWithOtherFiltersAndInclusiveDatesWithoutAnyBusinessWrites() {
        CurrentUser seller = actor("SELLER");
        Fixture succeeded = funded(seller, true),
                processing = funded(seller, true),
                excluded = funded(seller, true);
        var paid = settle(succeeded, support);
        command(
                seller,
                succeeded,
                paid.currentAttempt().id(),
                "SELLER_DISPUTE",
                new DisputeCommand(
                        paid.currentAttempt().receiptVersion(),
                        DisputeReason.NOT_RECEIVED,
                        "Awaiting seller bank credit"));
        claim(processing, support);
        claim(excluded, support);
        Fixture otherSeller = funded();
        claim(otherSeller, support);
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00Z"), to = from.plusDays(1);
        jdbc.update("UPDATE payouts SET created_at=? WHERE id=?", from, succeeded.id());
        jdbc.update("UPDATE payouts SET created_at=? WHERE id=?", to, processing.id());
        jdbc.update("UPDATE payouts SET created_at=? WHERE id=?", to.plusSeconds(1), excluded.id());
        var before = businessSnapshot(seller.id());
        var statuses = List.of(PayoutStatus.SUCCEEDED, PayoutStatus.PROCESSING);

        var newest =
                queries.queue(
                        support,
                        new ManualPayoutFilter(
                                seller.id(), statuses, "me", null, null, null, from, to, 0, 1));
        var oldest =
                queries.queue(
                        support,
                        new ManualPayoutFilter(
                                seller.id(), statuses, "me", null, null, null, from, to, 1, 1));
        var empty =
                queries.queue(
                        support,
                        new ManualPayoutFilter(
                                seller.id(), statuses, "me", null, null, null, from, to, 2, 1));
        assertThat(newest.content())
                .extracting(ManualPayoutView::id)
                .containsExactly(processing.id());
        assertThat(oldest.content())
                .extracting(ManualPayoutView::id)
                .containsExactly(succeeded.id());
        assertThat(empty.content()).isEmpty();
        assertThat(empty.page().totalElements()).isEqualTo(2);
        assertThat(empty.page().totalPages()).isEqualTo(2);
        var needsReview =
                queries.queue(
                        admin,
                        new ManualPayoutFilter(
                                seller.id(),
                                List.of(PayoutStatus.SUCCEEDED),
                                null,
                                PayoutReceiptStatus.DISPUTED,
                                true,
                                true,
                                from,
                                to,
                                0,
                                20));
        assertThat(needsReview.content())
                .extracting(ManualPayoutView::id)
                .containsExactly(succeeded.id());
        assertThat(needsReview.page().totalElements()).isEqualTo(1);
        queries.capabilities(support);
        queries.detail(support, succeeded.id());
        queries.sellerProjection(seller, succeeded.id());
        assertThat(businessSnapshot(seller.id())).isEqualTo(before);
    }

    @Test
    void readinessRequiresAnExplicitCommandAfterBankReviewAndCreatesNoAttempt() {
        Fixture f = funded(actor("SELLER"), false);
        assertThat(detail(f).status()).isEqualTo("ON_HOLD");
        var bank =
                banks.create(
                        f.seller(),
                        new BankAccountRequest(
                                "970436", "000000005678", "TEST SELLER", true, true));
        banks.verify(
                admin, bank.id(), new BankVerificationRequest("VERIFIED", null, bank.version()));
        assertThat(detail(f).status()).isEqualTo("ON_HOLD");
        var ready =
                command(
                                support,
                                f,
                                null,
                                "READINESS_CHECK",
                                new PayoutVersionCommand(detail(f).version(), null))
                        .result();
        assertThat(ready.status()).isEqualTo("PENDING");
        assertThat(ready.currentAttempt()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM manual_payout_attempts WHERE payout_id=?",
                                Long.class,
                                f.id()))
                .isZero();
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void evidenceReplayAndConcurrentQuotaKeepThreePrivateFilesAndRejectOtherOwners()
            throws Exception {
        Fixture f = funded();
        var issued = issue(f, support, claim(f, support));
        String uploadKey = key();
        var first =
                manual.upload(
                                support,
                                f.id(),
                                issued.currentAttempt().id(),
                                uploadKey,
                                issued.currentAttempt().version(),
                                proof())
                        .result();
        var second = evidence(f, support, first);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> successes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () ->
                                    succeedsAfter(
                                            start,
                                            () ->
                                                    manual.upload(
                                                            support,
                                                            f.id(),
                                                            second.currentAttempt().id(),
                                                            key(),
                                                            second.currentAttempt().version(),
                                                            proof())));
            var two =
                    pool.submit(
                            () ->
                                    succeedsAfter(
                                            start,
                                            () ->
                                                    manual.upload(
                                                            support,
                                                            f.id(),
                                                            second.currentAttempt().id(),
                                                            key(),
                                                            second.currentAttempt().version(),
                                                            proof())));
            start.countDown();
            successes = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
        }
        assertThat(successes.stream().filter(Boolean::booleanValue)).hasSize(1);
        assertThat(
                        manual.upload(
                                        support,
                                        f.id(),
                                        issued.currentAttempt().id(),
                                        uploadKey,
                                        issued.currentAttempt().version(),
                                        proof())
                                .replay())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM manual_payout_evidence WHERE payout_id=?",
                                Long.class,
                                f.id()))
                .isEqualTo(3);
        var current = detail(f);
        assertThatThrownBy(
                        () ->
                                manual.upload(
                                        admin,
                                        f.id(),
                                        issued.currentAttempt().id(),
                                        key(),
                                        current.currentAttempt().version(),
                                        proof()))
                .isInstanceOf(DomainException.class);
        Fixture other = funded();
        assertThatThrownBy(
                        () ->
                                manual.evidence(
                                        support,
                                        other.id(),
                                        issued.currentAttempt().id(),
                                        first.evidence().getFirst().id()))
                .isInstanceOf(DomainException.class);
        long puts =
                mockingDetails(storage).getInvocations().stream()
                        .filter(i -> "put".equals(i.getMethod().getName()))
                        .count();
        long deletes =
                mockingDetails(storage).getInvocations().stream()
                        .filter(i -> "delete".equals(i.getMethod().getName()))
                        .count();
        assertThat(puts).isBetween(3L, 4L);
        assertThat(puts - deletes).isEqualTo(3);
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void storageFailureLeavesNoEvidenceAuditVersionOrCommandBehind() {
        Fixture f = funded();
        var issued = issue(f, support, claim(f, support));
        var before = businessSnapshot(f.seller().id());
        doThrow(new IllegalStateException("Injected private storage failure"))
                .when(storage)
                .put(anyString(), any(), anyString());
        assertThatThrownBy(
                        () ->
                                manual.upload(
                                        support,
                                        f.id(),
                                        issued.currentAttempt().id(),
                                        key(),
                                        issued.currentAttempt().version(),
                                        proof()))
                .isInstanceOf(RuntimeException.class);
        assertThat(businessSnapshot(f.seller().id())).isEqualTo(before);
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void databaseFailureAfterObjectUploadRollsBackMetadataAndCleansTheObject() {
        Fixture f = funded();
        var issued = issue(f, support, claim(f, support));
        var before = businessSnapshot(f.seller().id());
        doThrow(
                        new org.springframework.dao.DataIntegrityViolationException(
                                "Injected metadata persistence failure"))
                .when(manualRepository)
                .insertEvidence(anyMap());
        assertThatThrownBy(
                        () ->
                                manual.upload(
                                        support,
                                        f.id(),
                                        issued.currentAttempt().id(),
                                        key(),
                                        issued.currentAttempt().version(),
                                        proof()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        doCallRealMethod().when(manualRepository).insertEvidence(anyMap());
        assertThat(businessSnapshot(f.seller().id())).isEqualTo(before);
        verify(storage).delete(anyString());
    }

    @Test
    void slowStorageHoldsNoDatabaseTransactionAndFinalizationRejectsAnInterveningBankChange()
            throws Exception {
        Fixture f = funded();
        var issued = issue(f, support, claim(f, support));
        UUID bank =
                jdbc.queryForObject(
                        "SELECT seller_bank_id FROM payouts WHERE id=?", UUID.class, f.id());
        CountDownLatch storageEntered = new CountDownLatch(1), allowUpload = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            storageEntered.countDown();
                            assertThat(allowUpload.await(10, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(storage)
                .put(anyString(), any(), anyString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var upload =
                    pool.submit(
                            () -> {
                                try {
                                    manual.upload(
                                            support,
                                            f.id(),
                                            issued.currentAttempt().id(),
                                            key(),
                                            issued.currentAttempt().version(),
                                            proof());
                                    return (RuntimeException) null;
                                } catch (RuntimeException conflict) {
                                    return conflict;
                                }
                            });
            assertThat(storageEntered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                // Completion here proves no upload transaction keeps the payout/group/bank locks
                // during HTTP.
                var changed =
                        pool.submit(
                                () ->
                                        banks.update(
                                                f.seller(),
                                                bank,
                                                new BankAccountRequest(
                                                        "970436",
                                                        "555555551234",
                                                        "TEST SELLER",
                                                        true,
                                                        true)));
                changed.get(5, TimeUnit.SECONDS);
            } finally {
                allowUpload.countDown();
            }
            assertThat(upload.get(5, TimeUnit.SECONDS)).isInstanceOf(DomainException.class);
        }
        assertThat(detail(f).status()).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM manual_payout_evidence WHERE payout_id=?",
                                Long.class,
                                f.id()))
                .isZero();
        verify(storage).delete(anyString());
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void outboxFailureRollsBackConfirmationReferenceLedgerAuditAndReplayMarker() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        var before = businessSnapshot(f.seller().id());
        String requestKey = key();
        var body = confirmation(ready, "FT" + key());
        doThrow(new IllegalStateException("Injected outbox failure"))
                .when(outboxWriter)
                .write(anyString(), anyString(), any());
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        support,
                                        f.id(),
                                        ready.currentAttempt().id(),
                                        null,
                                        "CONFIRM_TRANSFER",
                                        requestKey,
                                        body))
                .isInstanceOf(IllegalStateException.class);
        assertThat(businessSnapshot(f.seller().id())).isEqualTo(before);
        assertThat(movements(f, "PAYOUT")).isZero();
        assertThat(outbox(f, "PayoutSucceeded")).isZero();
        doCallRealMethod().when(outboxWriter).write(anyString(), anyString(), any());
        var settled =
                manual.execute(
                        support,
                        f.id(),
                        ready.currentAttempt().id(),
                        null,
                        "CONFIRM_TRANSFER",
                        requestKey,
                        body);
        assertThat(settled.replay()).isFalse();
        assertThat(settled.result().status()).isEqualTo("SUCCEEDED");
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
    }

    @Test
    void claimRaceHasOneOwnerAndReplayWinsBeforeItsOldVersionIsRejected() throws Exception {
        Fixture f = funded();
        long version = detail(f).version();
        CountDownLatch start = new CountDownLatch(1);
        String firstKey = key(), secondKey = key();
        ClaimOutcome one, two;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claimAfter(start, admin, f.id(), version, firstKey));
            var second = pool.submit(() -> claimAfter(start, support, f.id(), version, secondKey));
            start.countDown();
            one = first.get(20, TimeUnit.SECONDS);
            two = second.get(20, TimeUnit.SECONDS);
        }
        assertThat(List.of(one, two).stream().filter(outcome -> outcome.result() != null))
                .hasSize(1);
        ClaimOutcome winner = one.result() != null ? one : two;
        ClaimOutcome loser = one.result() == null ? one : two;
        assertThat(loser.error()).isInstanceOf(DomainException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM manual_payout_attempts WHERE payout_id=?",
                                Long.class,
                                f.id()))
                .isEqualTo(1);

        var replay =
                manual.execute(
                        winner.actor(),
                        f.id(),
                        null,
                        null,
                        "CLAIM",
                        winner.key(),
                        new ClaimCommand(version));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.result().currentAttempt().id())
                .isEqualTo(winner.result().result().currentAttempt().id());
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        winner.actor(),
                                        f.id(),
                                        null,
                                        null,
                                        "CLAIM",
                                        winner.key(),
                                        new ClaimCommand(version + 1)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        winner.actor(),
                                        f.id(),
                                        null,
                                        null,
                                        "CLAIM",
                                        key(),
                                        new ClaimCommand(version)))
                .isInstanceOf(DomainException.class);
        verify(gateway, never()).send(any());
        verify(gateway, never()).lookup(anyString());
    }

    @Test
    void releasedAttemptCannotIssueAndNextClaimHasANewReferenceOnTheSamePayout() {
        Fixture f = funded();
        var first = claim(f, support);
        var released =
                command(
                                support,
                                f,
                                first.currentAttempt().id(),
                                "RELEASE",
                                new AttemptVersionCommand(first.currentAttempt().version(), null))
                        .result();
        assertThat(released.status()).isEqualTo("PENDING");
        assertThat(released.currentAttempt().state()).isEqualTo("NOT_SENT");
        assertThatThrownBy(
                        () ->
                                command(
                                        support,
                                        f,
                                        first.currentAttempt().id(),
                                        "ISSUE_QR",
                                        new AttemptVersionCommand(
                                                first.currentAttempt().version(), null)))
                .isInstanceOf(DomainException.class);
        var second = claim(f, admin);
        assertThat(second.id()).isEqualTo(f.id());
        assertThat(second.currentAttempt().attemptNo()).isEqualTo(2);
        assertThat(second.currentAttempt().reference())
                .isNotEqualTo(first.currentAttempt().reference());
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void normalConfirmationAfterIssuanceExpiryCommitsUnknownBeforeReturningAConflict() {
        Fixture f = funded();
        var claimed = claim(f, support);
        settings.setQrDeadline(Duration.ofNanos(1));
        ManualPayoutView issued;
        try {
            issued = issue(f, support, claimed);
        } finally {
            settings.setQrDeadline(Duration.ofMinutes(10));
        }
        var ready = evidence(f, support, issued);
        String requestKey = key();
        var body = confirmation(ready, "FT" + key());
        var conflict =
                manual.execute(
                        support,
                        f.id(),
                        ready.currentAttempt().id(),
                        null,
                        "CONFIRM_TRANSFER",
                        requestKey,
                        body);
        assertThat(conflict.conflict()).isTrue();
        assertThat(detail(f).status()).isEqualTo("UNKNOWN");
        assertThat(detail(f).currentAttempt().state()).isEqualTo("UNKNOWN");
        assertThat(detail(f).cases()).hasSize(1);
        assertThat(
                        manual.execute(
                                        support,
                                        f.id(),
                                        ready.currentAttempt().id(),
                                        null,
                                        "CONFIRM_TRANSFER",
                                        requestKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isZero();
        assertThat(outbox(f, "PayoutSucceeded")).isZero();
    }

    @Test
    void issuedExpiryStaysUnknownAndDisablingIntakeDoesNotDiscardRecovery() {
        Fixture f = funded();
        var claimed = claim(f, support);
        settings.setQrDeadline(Duration.ofNanos(1));
        try {
            issue(f, support, claimed);
        } finally {
            settings.setQrDeadline(Duration.ofMinutes(10));
        }
        settings.setEnabled(false);
        manual.expire(f.id());
        var expired = detail(f);
        assertThat(expired.status()).isEqualTo("UNKNOWN");
        assertThat(expired.currentAttempt().state()).isEqualTo("UNKNOWN");
        assertThat(expired.cases()).hasSize(1);
        assertThatThrownBy(
                        () ->
                                command(
                                        admin,
                                        f,
                                        null,
                                        "RETRY",
                                        new PayoutVersionCommand(
                                                expired.version(), "Uncertain transfer")))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> manual.qr(support, f.id(), expired.currentAttempt().id()))
                .isInstanceOf(DomainException.class);
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    @Test
    void transferConfirmationPostsOnceAndSellerReceiptAddsNoFinancialMovement() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        String confirmationKey = key();
        var body = confirmation(ready, "FT" + key());
        var settled =
                manual.execute(
                        support,
                        f.id(),
                        ready.currentAttempt().id(),
                        null,
                        "CONFIRM_TRANSFER",
                        confirmationKey,
                        body);
        assertThat(settled.result().status()).isEqualTo("SUCCEEDED");
        assertThat(settled.result().confirmationSource()).isEqualTo("MANUAL_STAFF_CONFIRMED");
        assertThat(settled.result().currentAttempt().receiptStatus())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(
                        manual.execute(
                                        support,
                                        f.id(),
                                        ready.currentAttempt().id(),
                                        null,
                                        "CONFIRM_TRANSFER",
                                        confirmationKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(outbox(f, "PayoutSucceeded")).isEqualTo(1);
        assertFinancialHistory(f, "MANUAL_CONFIRMED", "PROCESSING", "SUCCEEDED", "PAYOUT");
        var a = settled.result().currentAttempt();
        String receiptKey = key();
        var received =
                manual.execute(
                        f.seller(),
                        f.id(),
                        a.id(),
                        null,
                        "SELLER_RECEIPT",
                        receiptKey,
                        new ReceiptCommand(a.receiptVersion()));
        assertThat(received.result().currentAttempt().receiptStatus()).isEqualTo("CONFIRMED");
        assertThat(
                        manual.execute(
                                        f.seller(),
                                        f.id(),
                                        a.id(),
                                        null,
                                        "SELLER_RECEIPT",
                                        receiptKey,
                                        new ReceiptCommand(a.receiptVersion()))
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        verify(gateway, never()).send(any());
        verify(gateway, never()).lookup(anyString());
    }

    @Test
    void bankReferenceConflictRollsBackTheSecondPostingAndKeepsItsIssuance() {
        Fixture first = funded(), second = funded();
        String reference = "FT" + key();
        var firstReady = evidence(first, support, issue(first, support, claim(first, support)));
        command(
                support,
                first,
                firstReady.currentAttempt().id(),
                "CONFIRM_TRANSFER",
                confirmation(firstReady, reference));
        var secondReady = evidence(second, support, issue(second, support, claim(second, support)));
        assertThatThrownBy(
                        () ->
                                command(
                                        support,
                                        second,
                                        secondReady.currentAttempt().id(),
                                        "CONFIRM_TRANSFER",
                                        confirmation(secondReady, reference)))
                .isInstanceOf(RuntimeException.class);
        assertThat(detail(second).status()).isEqualTo("PROCESSING");
        assertThat(detail(second).currentAttempt().state()).isEqualTo("QR_ISSUED");
        assertThat(movements(first, "PAYOUT")).isEqualTo(1);
        assertThat(movements(second, "PAYOUT")).isZero();
        assertThat(outbox(second, "PayoutSucceeded")).isZero();
    }

    @Test
    void adminCorrectionPreservesOriginalMovementAndRetryUsesTheSamePayout() {
        Fixture f = funded();
        var settled = settle(f, support);
        var disputed =
                command(
                                f.seller(),
                                f,
                                settled.currentAttempt().id(),
                                "SELLER_DISPUTE",
                                new DisputeCommand(
                                        settled.currentAttempt().receiptVersion(),
                                        DisputeReason.NOT_RECEIVED,
                                        "No transfer has arrived"))
                        .result();
        // Staff proof stays private to its executor or Admin reconciliation, even with queue
        // access.
        assertThatThrownBy(
                        () ->
                                command(
                                        support,
                                        f,
                                        settled.currentAttempt().id(),
                                        "RESOLVE",
                                        correction(detail(f))))
                .isInstanceOf(DomainException.class);
        String resolutionKey = key();
        var body = correction(detail(f));
        var corrected =
                manual.execute(
                                admin,
                                f.id(),
                                settled.currentAttempt().id(),
                                null,
                                "RESOLVE",
                                resolutionKey,
                                body)
                        .result();
        assertThat(corrected.status()).isEqualTo("FAILED");
        assertThat(corrected.currentAttempt().state()).isEqualTo("CONFIRMATION_CORRECTED");
        assertThat(
                        manual.execute(
                                        admin,
                                        f.id(),
                                        settled.currentAttempt().id(),
                                        null,
                                        "RESOLVE",
                                        resolutionKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_CORRECTION")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_RETURN")).isZero();
        assertThat(outbox(f, "PayoutConfirmationCorrected")).isEqualTo(1);
        assertFinancialHistory(
                f, "CONFIRMATION_CORRECTED", "SUCCEEDED", "FAILED", "PAYOUT_CORRECTION");
        command(
                admin,
                f,
                null,
                "RETRY",
                new PayoutVersionCommand(corrected.version(), "Non-execution reconciled"));
        var next = claim(f, admin);
        assertThat(next.id()).isEqualTo(f.id());
        assertThat(next.currentAttempt().attemptNo()).isEqualTo(2);
        assertThat(next.currentAttempt().receiptStatus()).isEqualTo("NOT_REQUESTED");
        assertThat(next.currentAttempt().reference())
                .isNotEqualTo(settled.currentAttempt().reference());
        assertThatThrownBy(
                        () ->
                                command(
                                        f.seller(),
                                        f,
                                        settled.currentAttempt().id(),
                                        "SELLER_RECEIPT",
                                        new ReceiptCommand(
                                                disputed.currentAttempt().receiptVersion())))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void anAdminCanDismissAMistakenStaffReportOnSuccessWithoutPostingOrChangingReceipt() {
        Fixture f = funded();
        var settled = settle(f, support);
        UUID attempt = settled.currentAttempt().id();
        command(
                support,
                f,
                attempt,
                "REPORT_PROBLEM",
                new ProblemCommand(
                        settled.currentAttempt().version(),
                        "Reported a problem by mistake",
                        settled.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        var current = detail(f);
        var review = current.cases().getFirst();
        var body = sentResolution(current, review.id());
        String requestKey = key();
        var moneyBefore = settledFinancialSnapshot(f);
        long audits =
                jdbc.queryForObject(
                        "SELECT count(*) FROM payout_history WHERE payout_id=? AND"
                            + " event='MANUAL_RESOLVED'",
                        Long.class,
                        f.id());
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        support,
                                        f.id(),
                                        attempt,
                                        review.id(),
                                        "RESOLVE",
                                        requestKey,
                                        body))
                .isInstanceOf(DomainException.class);
        var resolved =
                manual.execute(admin, f.id(), attempt, review.id(), "RESOLVE", requestKey, body);
        assertThat(resolved.result().status()).isEqualTo("SUCCEEDED");
        assertThat(resolved.result().currentAttempt().receiptStatus())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(resolved.result().cases())
                .filteredOn(c -> c.id().equals(review.id()))
                .extracting(ManualPayoutView.ReviewCase::status)
                .containsExactly("RESOLVED");
        assertThat(settledFinancialSnapshot(f)).isEqualTo(moneyBefore);
        assertThat(
                        manual.execute(
                                        admin,
                                        f.id(),
                                        attempt,
                                        review.id(),
                                        "RESOLVE",
                                        requestKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(settledFinancialSnapshot(f)).isEqualTo(moneyBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payout_history WHERE payout_id=? AND"
                                    + " event='MANUAL_RESOLVED'",
                                Long.class,
                                f.id()))
                .isEqualTo(audits + 1);
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_RETURN")).isZero();
        assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
        assertThat(outbox(f, "PayoutSucceeded")).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        admin,
                                        f.id(),
                                        attempt,
                                        review.id(),
                                        "RESOLVE",
                                        key(),
                                        body))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void dismissingOneSuccessfulStaffExceptionDoesNotCloseSellerDisputesOrOtherStaffCases() {
        Fixture f = funded();
        var settled = settle(f, support);
        UUID attempt = settled.currentAttempt().id();
        command(
                f.seller(),
                f,
                attempt,
                "SELLER_DISPUTE",
                new DisputeCommand(
                        settled.currentAttempt().receiptVersion(),
                        DisputeReason.NOT_RECEIVED,
                        "Seller still requests a receipt review"));
        var disputed = detail(f);
        command(
                support,
                f,
                attempt,
                "REPORT_PROBLEM",
                new ProblemCommand(
                        disputed.currentAttempt().version(),
                        "First mistaken staff report",
                        List.of()));
        var first = detail(f);
        UUID reviewed =
                first.cases().stream()
                        .filter(c -> "STAFF_EXCEPTION".equals(c.kind()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        command(
                support,
                f,
                attempt,
                "REPORT_PROBLEM",
                new ProblemCommand(
                        first.currentAttempt().version(), "Another independent report", List.of()));
        var current = detail(f);
        var before = settledFinancialSnapshot(f);
        var resolved =
                manual.execute(
                                admin,
                                f.id(),
                                attempt,
                                reviewed,
                                "RESOLVE",
                                key(),
                                sentResolution(current, reviewed))
                        .result();
        assertThat(resolved.cases())
                .filteredOn(c -> c.id().equals(reviewed))
                .extracting(ManualPayoutView.ReviewCase::status)
                .containsExactly("RESOLVED");
        assertThat(resolved.cases())
                .filteredOn(c -> !c.id().equals(reviewed))
                .extracting(ManualPayoutView.ReviewCase::status)
                .containsOnly("OPEN");
        assertThat(resolved.currentAttempt().receiptStatus()).isEqualTo("DISPUTED");
        assertThat(settledFinancialSnapshot(f)).isEqualTo(before);
        var sellerCase =
                current.cases().stream()
                        .filter(c -> "SELLER_DISPUTE".equals(c.kind()))
                        .findFirst()
                        .orElseThrow();
        assertThatThrownBy(
                        () ->
                                manual.execute(
                                        admin,
                                        f.id(),
                                        attempt,
                                        sellerCase.id(),
                                        "RESOLVE",
                                        key(),
                                        sentResolution(detail(f), sellerCase.id())))
                .isInstanceOf(DomainException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payout_review_cases WHERE id=?",
                                String.class,
                                sellerCase.id()))
                .isEqualTo("OPEN");
        assertThat(settledFinancialSnapshot(f)).isEqualTo(before);
    }

    @Test
    void dismissingAMistakenReportPreservesAnAlreadyConfirmedSellerReceipt() {
        Fixture f = funded();
        var settled = settle(f, support);
        UUID attempt = settled.currentAttempt().id();
        command(
                f.seller(),
                f,
                attempt,
                "SELLER_RECEIPT",
                new ReceiptCommand(settled.currentAttempt().receiptVersion()));
        var received = detail(f);
        command(
                support,
                f,
                attempt,
                "REPORT_PROBLEM",
                new ProblemCommand(
                        received.currentAttempt().version(),
                        "Accidental report after receipt",
                        List.of()));
        var current = detail(f);
        UUID review = current.cases().getFirst().id();
        var before = settledFinancialSnapshot(f);
        manual.execute(
                admin, f.id(), attempt, review, "RESOLVE", key(), sentResolution(current, review));
        assertThat(detail(f).currentAttempt().receiptStatus()).isEqualTo("CONFIRMED");
        assertThat(settledFinancialSnapshot(f)).isEqualTo(before);
    }

    @Test
    void reportingAnIssuedProblemWritesOneCaseLinkedAuditAndReplayAddsNothing() {
        Fixture f = funded();
        var issued = issue(f, support, claim(f, support));
        UUID attempt = issued.currentAttempt().id();
        String reportKey = key();
        var body =
                new ProblemCommand(
                        issued.currentAttempt().version(),
                        "External transfer result is uncertain",
                        List.of());
        var reported =
                manual.execute(support, f.id(), attempt, null, "REPORT_PROBLEM", reportKey, body)
                        .result();
        assertThat(reported.status()).isEqualTo("UNKNOWN");
        assertThat(reported.cases()).hasSize(1);
        var history =
                jdbc.queryForList(
                        "SELECT case_id,reason,from_status::text,to_status::text FROM"
                            + " payout_history WHERE payout_id=? AND event='MANUAL_PROBLEM'",
                        f.id());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst())
                .containsEntry("case_id", reported.cases().getFirst().id())
                .containsEntry("reason", body.reason())
                .containsEntry("from_status", "PROCESSING")
                .containsEntry("to_status", "UNKNOWN");
        assertThat(
                        manual.execute(
                                        support,
                                        f.id(),
                                        attempt,
                                        null,
                                        "REPORT_PROBLEM",
                                        reportKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payout_history WHERE payout_id=? AND"
                                    + " event='MANUAL_PROBLEM'",
                                Long.class,
                                f.id()))
                .isEqualTo(1);
        assertThat(movements(f, "PAYOUT")).isZero();
    }

    private ResolutionCommand sentResolution(ManualPayoutView view, UUID reviewId) {
        var review =
                view.cases().stream()
                        .filter(c -> c.id().equals(reviewId))
                        .findFirst()
                        .orElseThrow();
        return new ResolutionCommand(
                view.version(),
                view.currentAttempt().version(),
                review.version(),
                review.id(),
                ResolutionOutcome.CONFIRMED_SENT,
                "Reviewed transfer proof; staff report was mistaken",
                view.evidence().stream().map(ManualPayoutView.Evidence::id).toList(),
                null,
                null,
                null,
                null);
    }

    private List<String> settledFinancialSnapshot(Fixture f) {
        var rows = new ArrayList<String>();
        rows.add(
                jdbc.queryForObject(
                        "SELECT row_to_json(p)::text FROM payouts p WHERE id=?",
                        String.class,
                        f.id()));
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(a)::text FROM manual_payout_attempts a WHERE"
                            + " payout_id=? ORDER BY id",
                        String.class,
                        f.id()));
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM payment_transactions t WHERE session_id=?"
                            + " ORDER BY id",
                        String.class,
                        f.sessionId()));
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(o)::text FROM outbox o WHERE aggregate_id=? ORDER BY"
                            + " id",
                        String.class,
                        f.id().toString()));
        return rows;
    }

    @Test
    void genuineReturnCompensatesOnceAndAnExplicitRetryUsesANewAttempt() {
        Fixture f = funded();
        var settled = settle(f, support);
        command(
                support,
                f,
                settled.currentAttempt().id(),
                "REPORT_PROBLEM",
                new ProblemCommand(
                        settled.currentAttempt().version(),
                        "Bank transfer has actually returned",
                        settled.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        var current = detail(f);
        var review =
                current.cases().stream()
                        .filter(c -> !"RESOLVED".equals(c.status()))
                        .findFirst()
                        .orElseThrow();
        var body =
                new ResolutionCommand(
                        current.version(),
                        current.currentAttempt().version(),
                        review.version(),
                        review.id(),
                        ResolutionOutcome.CONFIRMED_RETURNED,
                        "Reviewed proof of actual bank return",
                        current.evidence().stream().map(ManualPayoutView.Evidence::id).toList(),
                        null,
                        null,
                        null,
                        null);
        String resolutionKey = key();
        var returned =
                manual.execute(
                                admin,
                                f.id(),
                                current.currentAttempt().id(),
                                null,
                                "RESOLVE",
                                resolutionKey,
                                body)
                        .result();
        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThat(
                        manual.execute(
                                        admin,
                                        f.id(),
                                        current.currentAttempt().id(),
                                        null,
                                        "RESOLVE",
                                        resolutionKey,
                                        body)
                                .replay())
                .isTrue();
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_RETURN")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
        assertFinancialHistory(f, "RETURNED", "SUCCEEDED", "RETURNED", "PAYOUT_RETURN");
        command(
                admin,
                f,
                null,
                "RETRY",
                new PayoutVersionCommand(returned.version(), "Actual bank return reconciled"));
        var next = claim(f, support);
        assertThat(next.currentAttempt().attemptNo()).isEqualTo(2);
        assertThat(next.currentAttempt().reference())
                .isNotEqualTo(settled.currentAttempt().reference());
    }

    @Test
    void bankChangeWithTheSameLastFourCannotReplaceAnIssuedSnapshotOrSettleNormally() {
        Fixture f = funded();
        var ready = evidence(f, support, issue(f, support, claim(f, support)));
        UUID attempt = ready.currentAttempt().id();
        String frozen =
                jdbc.queryForObject(
                        "SELECT account_encrypted FROM manual_payout_attempts WHERE id=?",
                        String.class,
                        attempt);
        UUID bank =
                jdbc.queryForObject(
                        "SELECT seller_bank_id FROM payouts WHERE id=?", UUID.class, f.id());
        var edited =
                banks.update(
                        f.seller(),
                        bank,
                        new BankAccountRequest(
                                "970436", "999999991234", "TEST SELLER", true, true));
        banks.verify(admin, bank, new BankVerificationRequest("VERIFIED", null, edited.version()));
        try {
            var result =
                    command(
                            support,
                            f,
                            attempt,
                            "CONFIRM_TRANSFER",
                            confirmation(ready, "FT" + key()));
            assertThat(result.conflict()).isTrue();
        } catch (DomainException expectedConflict) {
            // A synchronous after-commit bank-change handler may already have moved it to UNKNOWN.
        }
        assertThat(detail(f).status()).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT account_encrypted FROM manual_payout_attempts WHERE id=?",
                                String.class,
                                attempt))
                .isEqualTo(frozen);
        assertThat(movements(f, "PAYOUT")).isZero();
        assertThatThrownBy(
                        () ->
                                command(
                                        admin,
                                        f,
                                        null,
                                        "RETRY",
                                        new PayoutVersionCommand(
                                                detail(f).version(), "Bank changed")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void sellerAcknowledgementInvalidatesCorrectionEvenWhenAStaffExceptionRemainsOpen() {
        Fixture f = funded();
        var settled = settle(f, support);
        command(
                support,
                f,
                settled.currentAttempt().id(),
                "REPORT_PROBLEM",
                new ProblemCommand(
                        settled.currentAttempt().version(),
                        "Staff requests independent review",
                        settled.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));
        command(
                f.seller(),
                f,
                settled.currentAttempt().id(),
                "SELLER_DISPUTE",
                new DisputeCommand(
                        settled.currentAttempt().receiptVersion(),
                        DisputeReason.NOT_RECEIVED,
                        "Bank receipt delayed"));
        var beforeAcknowledgement = detail(f);
        var staffCase =
                beforeAcknowledgement.cases().stream()
                        .filter(c -> "STAFF_EXCEPTION".equals(c.kind()))
                        .findFirst()
                        .orElseThrow();
        var staleCorrection =
                new ResolutionCommand(
                        beforeAcknowledgement.version(),
                        beforeAcknowledgement.currentAttempt().version(),
                        staffCase.version(),
                        staffCase.id(),
                        ResolutionOutcome.CONFIRMATION_RECORDED_IN_ERROR,
                        "Correction prepared before seller response",
                        beforeAcknowledgement.evidence().stream()
                                .map(ManualPayoutView.Evidence::id)
                                .toList(),
                        null,
                        null,
                        null,
                        null);
        command(
                f.seller(),
                f,
                settled.currentAttempt().id(),
                "SELLER_RECEIPT",
                new ReceiptCommand(beforeAcknowledgement.currentAttempt().receiptVersion()));

        assertThatThrownBy(
                        () ->
                                command(
                                        admin,
                                        f,
                                        settled.currentAttempt().id(),
                                        "RESOLVE",
                                        staleCorrection))
                .isInstanceOf(DomainException.class);
        var received = detail(f);
        assertThat(received.status()).isEqualTo("SUCCEEDED");
        assertThat(received.currentAttempt().receiptStatus()).isEqualTo("CONFIRMED");
        assertThat(received.cases())
                .anyMatch(
                        c -> "STAFF_EXCEPTION".equals(c.kind()) && !"RESOLVED".equals(c.status()));
        assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
    }

    @Test
    void acknowledgementAndCorrectionRaceHaveExactlyOneValidOutcome() throws Exception {
        Fixture f = funded();
        var settled = settle(f, support);
        command(
                f.seller(),
                f,
                settled.currentAttempt().id(),
                "SELLER_DISPUTE",
                new DisputeCommand(
                        settled.currentAttempt().receiptVersion(),
                        DisputeReason.NOT_RECEIVED,
                        "Awaiting actual receipt"));
        var current = detail(f);
        var correction = correction(current);
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> successes;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var received =
                    pool.submit(
                            () ->
                                    succeedsAfter(
                                            start,
                                            () ->
                                                    command(
                                                            f.seller(),
                                                            f,
                                                            current.currentAttempt().id(),
                                                            "SELLER_RECEIPT",
                                                            new ReceiptCommand(
                                                                    current.currentAttempt()
                                                                            .receiptVersion()))));
            var corrected =
                    pool.submit(
                            () ->
                                    succeedsAfter(
                                            start,
                                            () ->
                                                    command(
                                                            admin,
                                                            f,
                                                            current.currentAttempt().id(),
                                                            "RESOLVE",
                                                            correction)));
            start.countDown();
            successes =
                    List.of(
                            received.get(20, TimeUnit.SECONDS),
                            corrected.get(20, TimeUnit.SECONDS));
        }
        assertThat(successes.stream().filter(Boolean::booleanValue)).hasSize(1);
        var result = detail(f);
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        if ("SUCCEEDED".equals(result.status())) {
            assertThat(result.currentAttempt().receiptStatus()).isEqualTo("CONFIRMED");
            assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
        } else {
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.currentAttempt().state()).isEqualTo("CONFIRMATION_CORRECTED");
            assertThat(movements(f, "PAYOUT_CORRECTION")).isEqualTo(1);
        }
    }

    private ManualPayoutView claim(Fixture f, CurrentUser actor) {
        return command(actor, f, null, "CLAIM", new ClaimCommand(detail(f).version())).result();
    }

    private ManualPayoutView issue(Fixture f, CurrentUser actor, ManualPayoutView claimed) {
        return command(
                        actor,
                        f,
                        claimed.currentAttempt().id(),
                        "ISSUE_QR",
                        new AttemptVersionCommand(claimed.currentAttempt().version(), null))
                .result();
    }

    private ManualPayoutView evidence(Fixture f, CurrentUser actor, ManualPayoutView issued) {
        return manual.upload(
                        actor,
                        f.id(),
                        issued.currentAttempt().id(),
                        key(),
                        issued.currentAttempt().version(),
                        proof())
                .result();
    }

    private static MockMultipartFile proof() {
        return new MockMultipartFile(
                "file",
                "proof.pdf",
                "application/pdf",
                "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\n%%EOF"
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private ManualPayoutView settle(Fixture f, CurrentUser actor) {
        var ready = evidence(f, actor, issue(f, actor, claim(f, actor)));
        return command(
                        actor,
                        f,
                        ready.currentAttempt().id(),
                        "CONFIRM_TRANSFER",
                        confirmation(ready, "FT" + key()))
                .result();
    }

    private ConfirmationCommand confirmation(ManualPayoutView view, String reference) {
        return new ConfirmationCommand(
                view.currentAttempt().version(),
                "platform-main",
                view.netAmount(),
                reference,
                OffsetDateTime.now(ZoneOffset.UTC),
                view.evidence().stream().map(ManualPayoutView.Evidence::id).toList());
    }

    private ResolutionCommand correction(ManualPayoutView view) {
        var review =
                view.cases().stream()
                        .filter(c -> !"RESOLVED".equals(c.status()))
                        .findFirst()
                        .orElseThrow();
        return new ResolutionCommand(
                view.version(),
                view.currentAttempt().version(),
                review.version(),
                review.id(),
                ResolutionOutcome.CONFIRMATION_RECORDED_IN_ERROR,
                "Reviewed non-execution proof",
                view.evidence().stream().map(ManualPayoutView.Evidence::id).toList(),
                null,
                null,
                null,
                null);
    }

    private ManualCommandResult command(
            CurrentUser actor, Fixture f, UUID attempt, String operation, Object body) {
        return manual.execute(actor, f.id(), attempt, null, operation, key(), body);
    }

    private ManualPayoutView detail(Fixture f) {
        return queries.detail(admin, f.id());
    }

    private long movements(Fixture f, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND type::text=?",
                Long.class,
                f.sessionId(),
                type);
    }

    private long outbox(Fixture f, String event) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type=?",
                Long.class,
                f.id().toString(),
                event);
    }

    private Map<String, Object> financialHistory(Fixture f, String event) {
        return jdbc.queryForMap(
                "SELECT from_status,to_status,transaction_id FROM payout_history WHERE payout_id=?"
                        + " AND event::text=?",
                f.id(),
                event);
    }

    private void assertFinancialHistory(
            Fixture f, String event, String from, String to, String type) {
        var history = financialHistory(f, event);
        assertThat(history.get("from_status").toString()).isEqualTo(from);
        assertThat(history.get("to_status").toString()).isEqualTo(to);
        UUID transaction =
                jdbc.queryForObject(
                        "SELECT id FROM payment_transactions WHERE session_id=? AND type::text=?",
                        UUID.class,
                        f.sessionId(),
                        type);
        assertThat(history.get("transaction_id")).isEqualTo(transaction);
    }

    private ClaimOutcome claimAfter(
            CountDownLatch start, CurrentUser actor, UUID payout, long version, String key)
            throws InterruptedException {
        return executeAfter(start, actor, payout, null, "CLAIM", key, new ClaimCommand(version));
    }

    private ClaimOutcome executeAfter(
            CountDownLatch start,
            CurrentUser actor,
            UUID payout,
            UUID attempt,
            String operation,
            String key,
            Object body)
            throws InterruptedException {
        start.await();
        try {
            return new ClaimOutcome(
                    actor,
                    key,
                    manual.execute(actor, payout, attempt, null, operation, key, body),
                    null);
        } catch (RuntimeException failure) {
            return new ClaimOutcome(actor, key, null, failure);
        }
    }

    private boolean succeedsAfter(CountDownLatch start, Runnable command)
            throws InterruptedException {
        start.await();
        try {
            command.run();
            return true;
        } catch (DomainException conflict) {
            return false;
        }
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static CurrentUser actor(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of());
    }

    private record ClaimOutcome(
            CurrentUser actor, String key, ManualCommandResult result, RuntimeException error) {}

    private record Fixture(UUID id, UUID orderId, UUID sessionId, CurrentUser seller) {}

    private List<String> businessSnapshot(UUID sellerId) {
        List<String> rows = new ArrayList<>();
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(p)::text FROM payouts p WHERE seller_id=? ORDER BY id",
                        String.class,
                        sellerId));
        for (String table :
                List.of(
                        "payout_history",
                        "manual_payout_attempts",
                        "manual_payout_evidence",
                        "payout_review_cases",
                        "manual_payout_commands")) {
            rows.addAll(
                    jdbc.queryForList(
                            "SELECT row_to_json(x)::text FROM "
                                    + table
                                    + " x JOIN payouts p ON p.id=x.payout_id WHERE p.seller_id=?"
                                    + " ORDER BY x."
                                    + ("manual_payout_commands".equals(table)
                                            ? "idempotency_key"
                                            : "id"),
                            String.class,
                            sellerId));
        }
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(t)::text FROM payment_transactions t JOIN payouts p ON"
                                + " p.session_id=t.session_id WHERE p.seller_id=? ORDER BY t.id",
                        String.class,
                        sellerId));
        rows.addAll(
                jdbc.queryForList(
                        "SELECT row_to_json(o)::text FROM outbox o JOIN payouts p ON"
                                + " p.id::text=o.aggregate_id WHERE p.seller_id=? ORDER BY o.id",
                        String.class,
                        sellerId));
        return rows;
    }

    private Fixture funded() {
        return funded(actor("SELLER"), true);
    }

    private Fixture funded(CurrentUser seller, boolean createBank) {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        UUID session =
                jdbc.queryForObject(
                        """
INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,source_event_id,idempotency_key,paid_at)
VALUES (?,?,100000,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour') RETURNING id
""",
                        UUID.class,
                        group,
                        buyer,
                        group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                        UUID.randomUUID(),
                        key());
        UUID funding =
                jdbc.queryForObject(
                        """
INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)
VALUES (?,'SEPAY',?,100000,'SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
""",
                        UUID.class,
                        session,
                        key(),
                        key());
        jdbc.update(
                """
INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,due_at,status,paid_at,last_order_version)
VALUES (?,?,?,?,?,100000,now()+interval '1 hour','PAID',now()-interval '1 hour',1)
""",
                order,
                session,
                funding,
                buyer,
                seller.id());
        if (createBank
                && jdbc.queryForObject(
                                "SELECT count(*) FROM seller_bank WHERE seller_id=? AND active AND"
                                        + " is_default AND verification_status='VERIFIED'",
                                Long.class,
                                seller.id())
                        == 0) {
            var bank =
                    banks.create(
                            seller,
                            new BankAccountRequest(
                                    "970436", "000000001234", "TEST SELLER", true, true));
            banks.verify(
                    admin,
                    bank.id(),
                    new BankVerificationRequest("VERIFIED", null, bank.version()));
        }
        Instant completed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        completions.completed(
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCompleted",
                        order.toString(),
                        completed,
                        "manual-flow-test",
                        new OrderCompleted(
                                order,
                                group,
                                buyer,
                                seller.id(),
                                "STANDARD",
                                null,
                                4,
                                completed,
                                "NX-" + order,
                                "100000.00",
                                "5000.00")));
        return new Fixture(
                jdbc.queryForObject("SELECT id FROM payouts WHERE order_id=?", UUID.class, order),
                order,
                session,
                seller);
    }
}
