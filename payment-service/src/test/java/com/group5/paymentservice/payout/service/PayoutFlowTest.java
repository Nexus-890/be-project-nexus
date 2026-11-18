package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.group5.common_events.EventEnvelope;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.dto.request.PayoutListRequest;
import com.group5.paymentservice.payout.dto.response.PayoutView;
import com.group5.paymentservice.payout.enums.PayoutStatus;
import com.group5.paymentservice.payout.provider.PayoutAttemptKey;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.repository.PayoutDispatchRepository;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

@TestPropertySource(
        properties = {
            "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "nexus.payment.payout-eligibility.cron=-",
            "nexus.payout.callback-secret=test-callback-secret-only"
        })
class PayoutFlowTest extends PaymentDatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PayoutEligibilityService completions;
    @Autowired PayoutService payouts;
    @Autowired PayoutDispatchService dispatcher;
    @Autowired PayoutProperties properties;
    @Autowired BankAccountService banks;
    @Autowired PayoutQueryService queries;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired PayoutDispatchRepository dispatches;
    @Autowired org.springframework.transaction.support.TransactionTemplate payoutTransactions;
    @MockitoSpyBean PayoutGateway gateway;
    private final CurrentUser admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());

    @BeforeEach
    void resetConfig() {
        // These tests explicitly exercise the legacy AUTO dispatcher; scheduled jobs stay absent.
        properties.setExecutionMode("AUTO");
        properties.setJobsEnabled(true);
        properties.setPlatformFeeRate(BigDecimal.ZERO);
        properties.setSandboxOutcome("SUCCESS");
        properties.setMaxAttempts(3);
        jdbc.update(
                "UPDATE payouts SET next_attempt_at=now()+interval '1 year' WHERE"
                        + " status='PENDING'");
        doAnswer(
                        call -> {
                            var transfer = call.getArgument(0, PayoutGateway.Transfer.class);
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM payout_history WHERE"
                                                            + " payout_id=? AND event='SENT' AND"
                                                            + " attempt_no=?",
                                                    Integer.class,
                                                    transfer.payoutId(),
                                                    transfer.attempt()))
                                    .isEqualTo(1);
                            return call.callRealMethod();
                        })
                .when(gateway)
                .send(any());
    }

    @Test
    void manualLiabilityKeepsItsModeAndCannotEnterAnyLegacyAutomaticPath() {
        properties.setExecutionMode("MANUAL");
        properties.setJobsEnabled(false);
        Fixture f = funded("100000.00", "5000.00");
        verifyBank(f.seller(), "000000008888");
        completions.completed(event(f));
        UUID id = id(f);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_mode FROM payouts WHERE id=?", String.class, id))
                .isEqualTo("MANUAL");
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PENDING");

        // A later deployment may explicitly run legacy AUTO jobs; an existing MANUAL row stays
        // excluded.
        properties.setExecutionMode("AUTO");
        properties.setJobsEnabled(true);
        jdbc.update("UPDATE payouts SET next_attempt_at=now()-interval '1 day' WHERE id=?", id);
        assertThat(dispatcher.dispatchOne()).isFalse();
        jdbc.update(
                "UPDATE payouts SET status='PROCESSING',attempt_started_at=now()-interval '1 day'"
                    + " WHERE id=?",
                id);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var automaticClaim =
                payoutTransactions.execute(
                        status ->
                                dispatches.claimNextReconciliation(
                                        "manual", now, now.plusMinutes(5)));
        assertThat(automaticClaim).isEmpty();
        assertThat(dispatches.findUnresolvedAttempt(id, 0)).isEmpty();
        payouts.apply(
                id,
                0,
                "manual",
                new PayoutGateway.Result(PayoutGateway.State.SUCCEEDED, "fabricated", "SUCCESS"),
                true);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PROCESSING");
        jdbc.update("UPDATE payouts SET status='FAILED' WHERE id=?", id);
        assertThatThrownBy(() -> payouts.retry(admin, id, UUID.randomUUID().toString()))
                .isInstanceOf(com.group5.common_core.exception.DomainException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                    + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isZero();
        verify(gateway, never()).send(any());
        verify(gateway, never()).lookup(anyString());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE payouts SET execution_mode='AUTO' WHERE id=?", id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void fractionalManualLiabilityIsHeldAndBankVerificationCannotMakeItAutomaticallyExecutable() {
        properties.setExecutionMode("MANUAL");
        properties.setJobsEnabled(false);
        Fixture f = funded("100000.40", "5000.00");
        verifyBank(f.seller(), "000000007777");
        completions.completed(event(f));
        UUID id = id(f);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("ON_HOLD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT blocked_reason FROM payouts WHERE id=?", String.class, id))
                .isEqualTo("UNSUPPORTED_AMOUNT");
        payouts.releaseHeld(f.seller().id());
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("ON_HOLD");
        assertThat(queries.detail(f.seller(), id).netAmount()).isEqualByComparingTo("95000.40");
    }

    @Test
    void verifiedButUnsupportedManualRecipientStartsHeld() {
        properties.setExecutionMode("MANUAL");
        properties.setJobsEnabled(false);
        Fixture f = funded("100000.00", "5000.00");
        var bank =
                banks.create(
                        f.seller(),
                        new BankAccountRequest(
                                "999999", "000000001234", "TEST SELLER", true, true));
        banks.verify(
                admin, bank.id(), new BankVerificationRequest("VERIFIED", null, bank.version()));
        completions.completed(event(f));
        UUID id = id(f);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("ON_HOLD");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT blocked_reason FROM payouts WHERE id=?", String.class, id))
                .isEqualTo("UNSUPPORTED_BANK_ACCOUNT");
        assertThat(queries.detail(f.seller(), id).netAmount()).isEqualByComparingTo("95000.00");
    }

    @Test
    void completionCreatesOneHeldPayoutWithFrozenFeeAndVerificationAutomaticallyReleasesIt() {
        properties.setPlatformFeeRate(new BigDecimal("0.0125"));
        Fixture f = funded("100000.40", "5000.00");
        var event = event(f);
        completions.completed(event);
        completions.completed(event);
        completions.completed(
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        event.eventType(),
                        event.aggregateId(),
                        event.occurredAt(),
                        event.traceId(),
                        event.payload()));
        UUID id = id(f);
        var p = queries.detail(f.seller(), id);
        assertThat(p.status()).isEqualTo("ON_HOLD");
        assertThat(p.platformFee()).isEqualByComparingTo("1187.51");
        assertThat(p.netAmount()).isEqualByComparingTo("93812.89");
        assertThat(p.history())
                .extracting(PayoutView.History::event)
                .containsExactly("HELD", "CREATED");
        properties.setPlatformFeeRate(new BigDecimal("0.9"));
        verifyBank(f.seller(), "000000001234");
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PENDING");
        assertThat(queries.detail(f.seller(), id).platformFeeRate()).isEqualByComparingTo("0.0125");
        dispatch(id);
        p = queries.detail(f.seller(), id);
        assertThat(p.status()).isEqualTo("SUCCEEDED");
        assertThat(p.history()).allMatch(PayoutView.History::simulated);
        assertThat(p.recipientAccountLast4()).isEqualTo("1234");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payouts WHERE order_id=?",
                                Integer.class,
                                f.order()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox WHERE aggregate_id=? AND"
                                        + " event_type='PayoutSucceeded'",
                                Integer.class,
                                id.toString()))
                .isEqualTo(1);
        // Luồng đọc của buyer chỉ thấy giao dịch NORMAL ban đầu, không lẫn tiền payout.
        assertThat(transactions.findViewsBySession(f.session())).hasSize(1);
    }

    @Test
    void refundUnfundedAndZeroNetNeverCreatePayout() {
        for (String reason : List.of("refund", "unfunded", "zero")) {
            Fixture f = funded("100000.00", reason.equals("zero") ? "100000.00" : "5000.00");
            if (reason.equals("refund"))
                jdbc.update(
                        "INSERT INTO refunds(order_id,amount,reason,idempotency_key,requested_by)"
                                + " VALUES (?,100000,'test',?,?)",
                        f.order(),
                        UUID.randomUUID().toString(),
                        admin.id());
            if (reason.equals("unfunded"))
                jdbc.update(
                        "UPDATE payment_instructions SET"
                                + " status='PENDING',funded_transaction_id=NULL WHERE order_id=?",
                        f.order());
            completions.completed(event(f));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM payouts WHERE order_id=?",
                                    Integer.class,
                                    f.order()))
                    .as(reason)
                    .isZero();
            assertThat(
                            queries.admin(
                                            admin,
                                            new PayoutListRequest(
                                                    f.seller().id(), null, null, null, 0, 20))
                                    .content())
                    .as(reason + " is absent from the administrator list")
                    .isEmpty();
        }
    }

    @Test
    void completedFundedOrderAppearsInAdminListWithoutAnAdministratorCreatingIt() {
        Fixture f = funded("100000.00", "5000.00");
        var completed = event(f);
        completions.completed(completed);
        completions.completed(completed);
        var result =
                queries.admin(
                        admin,
                        new PayoutListRequest(
                                f.seller().id(), List.of(PayoutStatus.ON_HOLD), null, null, 0, 20));
        assertThat(result.page().totalElements()).isEqualTo(1);
        assertThat(result.content())
                .singleElement()
                .satisfies(
                        payout -> {
                            assertThat(payout.orderId()).isEqualTo(f.order());
                            assertThat(payout.netAmount()).isEqualByComparingTo("95000.00");
                            assertThat(payout.updatedAt()).isNotNull();
                        });
    }

    @Test
    void timeoutNeverStartsANewAttemptAndReconciliationUsesOriginalKey() {
        Fixture f = ready();
        UUID id = id(f);
        properties.setSandboxOutcome("TIMEOUT");
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("UNKNOWN");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isZero();
        dispatcher.dispatchOne();
        assertThat(queries.detail(f.seller(), id).attemptCount()).isEqualTo(1);
        jdbc.update("UPDATE payouts SET attempt_started_at=now()-interval '1 hour' WHERE id=?", id);
        dispatcher.reconcile();
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("SUCCEEDED");
        assertThat(queries.detail(f.seller(), id).attemptCount()).isEqualTo(1);
        assertThat(queries.detail(f.seller(), id).history())
                .extracting(PayoutView.History::event)
                .contains("TIMED_OUT", "RECONCILED");
    }

    @Test
    void definitiveFailureRetriesWithBackoffThenAdminRetryIsIdempotent() {
        Fixture f = ready();
        UUID id = id(f);
        properties.setSandboxOutcome("FAILURE");
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT next_attempt_at>now() FROM payouts WHERE id=?",
                                Boolean.class,
                                id))
                .isTrue();
        properties.setMaxAttempts(2);
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM outbox WHERE aggregate_id=? AND"
                                        + " event_type='PayoutFailed'",
                                Integer.class,
                                id.toString()))
                .isEqualTo(1);
        String request = UUID.randomUUID().toString();
        payouts.retry(admin, id, request);
        payouts.retry(admin, id, request);
        properties.setSandboxOutcome("SUCCESS");
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("SUCCEEDED");
        assertThat(queries.detail(f.seller(), id).attemptCount()).isEqualTo(3);
        assertThat(queries.detail(f.seller(), id).history())
                .anyMatch(h -> h.event().equals("RETRIED") && h.actorId().equals(admin.id()));
        assertThatThrownBy(() -> payouts.retry(admin, id, UUID.randomUUID().toString()))
                .hasMessageContaining("retried");
    }

    @Test
    void returnAndRetryKeepEveryMovementAndOldRecipientSnapshotAndIgnoreLateCallback() {
        Fixture f = ready();
        UUID id = id(f);
        properties.setSandboxOutcome("RETURN");
        dispatch(id);
        var first = queries.detail(f.seller(), id);
        assertThat(first.status()).isEqualTo("SUCCEEDED");
        jdbc.update("UPDATE payouts SET next_attempt_at=now()-interval '1 second' WHERE id=?", id);
        dispatcher.reconcile();
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("RETURNED");
        verifyBank(f.seller(), "000000009999");
        payouts.retry(admin, id, UUID.randomUUID().toString());
        properties.setSandboxOutcome("SUCCESS");
        dispatch(id);
        var second = queries.detail(f.seller(), id);
        assertThat(second.status()).isEqualTo("SUCCEEDED");
        assertThat(second.recipientAccountLast4()).isEqualTo("9999");
        assertThat(second.history())
                .filteredOn(h -> h.event().equals("SENT"))
                .extracting(PayoutView.History::recipientAccountLast4)
                .containsExactly("9999", "1234");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT_RETURN'",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        payouts.apply(
                id,
                1,
                "sandbox",
                new PayoutGateway.Result(
                        PayoutGateway.State.RETURNED, first.providerReference(), "RETURNED"),
                false);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("SUCCEEDED");
        assertThat(second.history())
                .filteredOn(h -> h.transactionId() != null)
                .allMatch(h -> h.recipientAccountLast4() != null);
    }

    @Test
    void concurrentDispatchAndDuplicateSuccessCannotSendOrRecordTwice() throws Exception {
        Fixture f = ready();
        UUID id = id(f);
        jdbc.update("UPDATE payouts SET next_attempt_at=now()-interval '1 day' WHERE id=?", id);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return dispatcher.dispatchOne();
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return dispatcher.dispatchOne();
                            });
            gate.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }
        var p = queries.detail(f.seller(), id);
        assertThat(p.attemptCount()).isEqualTo(1);
        assertThat(p.history()).filteredOn(h -> h.event().equals("SENT")).hasSize(1);
        var result =
                new PayoutGateway.Result(
                        PayoutGateway.State.SUCCEEDED, p.providerReference(), "SUCCESS");
        payouts.apply(id, 1, "sandbox", result, false);
        payouts.apply(id, 1, "sandbox", result, false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                queries.detail(
                                        new CurrentUser(
                                                UUID.randomUUID(), Set.of("SELLER"), Set.of()),
                                        id))
                .hasMessageContaining("not available");
    }

    @Test
    void crashedProcessingIsReconciledAndAcceptedIsNeverSuccess() {
        Fixture f = ready();
        UUID id = id(f);
        properties.setSandboxOutcome("ACCEPTED");
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PROCESSING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isZero();
        jdbc.update("UPDATE payouts SET attempt_started_at=now()-interval '1 hour' WHERE id=?", id);
        dispatcher.reconcile();
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void releaseSweepRepairsMissedVerificationAndDirectReturnPreservesCoherentHistory() {
        Fixture f = funded("100000.00", "5000.00");
        completions.completed(event(f));
        UUID id = id(f);
        // Mô phỏng verify đã commit nhưng listener chưa giải phóng payout để kiểm tra quét phục
        // hồi.
        var b =
                banks.create(
                        f.seller(),
                        new BankAccountRequest("970415", "000000001234", "TEST", true, true));
        jdbc.update(
                "UPDATE seller_bank SET"
                    + " verification_status='VERIFIED',verified_by=?,verified_at=now() WHERE id=?",
                admin.id(),
                b.id());
        dispatcher.reconcile();
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("PENDING");
        properties.setSandboxOutcome("ACCEPTED");
        dispatch(id);
        payouts.apply(
                id,
                1,
                "sandbox",
                new PayoutGateway.Result(
                        PayoutGateway.State.RETURNED,
                        queries.detail(f.seller(), id).providerReference(),
                        "RETURNED"),
                false);
        var p = queries.detail(f.seller(), id);
        assertThat(p.status()).isEqualTo("RETURNED");
        assertThat(p.history().getFirst().fromStatus()).isEqualTo("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type IN ('PAYOUT','PAYOUT_RETURN')",
                                Integer.class,
                                f.session()))
                .isEqualTo(2);
    }

    @Test
    void sendExceptionStaysUnknownAndDoesNotGenerateMovementOrNewAttempt() {
        Fixture f = ready();
        UUID id = id(f);
        doThrow(new IllegalStateException("simulated network interruption"))
                .when(gateway)
                .send(any());
        dispatch(id);
        assertThat(queries.detail(f.seller(), id).status()).isEqualTo("UNKNOWN");
        assertThat(queries.detail(f.seller(), id).attemptCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='PAYOUT'",
                                Integer.class,
                                f.session()))
                .isZero();
        assertThatThrownBy(() -> payouts.retry(admin, id, UUID.randomUUID().toString()))
                .hasMessageContaining("retried");
    }

    @Test
    void crashBeforeProviderAcceptedReissuesTheSameFrozenAttemptInsteadOfCreatingAnother() {
        Fixture f = ready();
        UUID id = id(f);
        properties.setSandboxOutcome("TIMEOUT");
        dispatch(id);
        verifyBank(f.seller(), "000000009999");
        doReturn(new PayoutGateway.Result(PayoutGateway.State.NOT_FOUND, null, "NOT_FOUND"))
                .when(gateway)
                .lookup(PayoutAttemptKey.of(id, 1));
        properties.setSandboxOutcome("SUCCESS");
        jdbc.update("UPDATE payouts SET attempt_started_at=now()-interval '1 hour' WHERE id=?", id);
        dispatcher.reconcile();
        var p = queries.detail(f.seller(), id);
        assertThat(p.status()).isEqualTo("SUCCEEDED");
        assertThat(p.attemptCount()).isEqualTo(1);
        assertThat(p.recipientAccountLast4()).isEqualTo("1234");
        verify(gateway, times(2))
                .send(
                        argThat(
                                t ->
                                        t.payoutId().equals(id)
                                                && t.attempt() == 1
                                                && t.key().equals(PayoutAttemptKey.of(id, 1))
                                                && t.accountNumber().equals("000000001234")));
    }

    private Fixture ready() {
        Fixture f = funded("100000.00", "5000.00");
        verifyBank(f.seller(), "000000001234");
        completions.completed(event(f));
        return f;
    }

    private void verifyBank(CurrentUser seller, String number) {
        var b =
                banks.create(
                        seller,
                        new BankAccountRequest("970415", number, "TEST SELLER", true, true));
        banks.verify(admin, b.id(), new BankVerificationRequest("VERIFIED", null, b.version()));
    }

    private UUID id(Fixture f) {
        return jdbc.queryForObject(
                "SELECT id FROM payouts WHERE order_id=?", UUID.class, f.order());
    }

    private void dispatch(UUID id) {
        jdbc.update("UPDATE payouts SET next_attempt_at=now()-interval '2 days' WHERE id=?", id);
        assertThat(dispatcher.dispatchOne()).isTrue();
    }

    private EventEnvelope<OrderCompleted> event(Fixture f) {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return new EventEnvelope<>(
                UUID.randomUUID(),
                "OrderCompleted",
                f.order().toString(),
                now,
                "payout-flow-test",
                new OrderCompleted(
                        f.order(),
                        f.group(),
                        f.buyer(),
                        f.seller().id(),
                        "STANDARD",
                        null,
                        4,
                        now,
                        "NX-" + f.order(),
                        f.amount(),
                        f.shipping()));
    }

    private Fixture funded(String amount, String shipping) {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        var seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        UUID session =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,source_event_id,idempotency_key,paid_at)"
                            + " VALUES (?,?,?,'VND',?,now()+interval '1"
                            + " hour','PAID',?,?,now()-interval '1 hour') RETURNING id",
                        UUID.class,
                        group,
                        buyer,
                        new BigDecimal(amount),
                        group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                        UUID.randomUUID(),
                        UUID.randomUUID().toString());
        UUID transaction =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)"
                            + " VALUES (?,'SEPAY',?,?,'SUCCESS','NORMAL',?,now()-interval '1 hour')"
                            + " RETURNING id",
                        UUID.class,
                        session,
                        UUID.randomUUID().toString(),
                        new BigDecimal(amount),
                        UUID.randomUUID().toString());
        jdbc.update(
                "INSERT INTO"
                    + " payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,due_at,status,paid_at,last_order_version)"
                    + " VALUES (?,?,?,?,?,?,now()+interval '1 hour','PAID',now()-interval '1"
                    + " hour',1)",
                order,
                session,
                transaction,
                buyer,
                seller.id(),
                new BigDecimal(amount));
        return new Fixture(order, group, buyer, seller, session, amount, shipping);
    }

    private record Fixture(
            UUID order,
            UUID group,
            UUID buyer,
            CurrentUser seller,
            UUID session,
            String amount,
            String shipping) {}
}
