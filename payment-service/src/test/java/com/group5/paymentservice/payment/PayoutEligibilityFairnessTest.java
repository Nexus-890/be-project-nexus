package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.job.PayoutEligibilityJob;
import com.group5.paymentservice.payout.PayoutProperties;
import com.group5.paymentservice.payout.PayoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@TestPropertySource(properties = "nexus.payment.payout-eligibility.cron=-")
class PayoutEligibilityFairnessTest extends PaymentDatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired PayoutEligibilityJob job;
    @Autowired PayoutProperties properties;
    @MockitoSpyBean PayoutService payouts;

    @BeforeEach
    void defaultFee() {
        properties.setPlatformFeeRate(BigDecimal.ZERO);
        reset(payouts);
    }

    @Test
    void oneHundredZeroNetCompletionsCannotStarveALaterOrderAfterFundingArrives() {
        List<Fixture> orders = seedQueue("100.00", "100.00", "100.00", "10.00", false);
        Fixture recoverable = orders.getLast();
        fund(recoverable);

        job.run();
        job.run();

        assertThat(payoutCount(recoverable)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT net_amount FROM payouts WHERE order_id=?",
                BigDecimal.class, recoverable.orderId())).isEqualByComparingTo("90.00");
        assertThat(orders.subList(0, 100)).allSatisfy(order -> assertThat(payoutCount(order)).isZero());
    }

    @Test
    void roundedZeroNetOrdersDoNotBlockLaterPagesAndCanRecoverAfterTheConfiguredRateChanges() {
        properties.setPlatformFeeRate(new BigDecimal("0.5"));
        List<Fixture> orders = seedQueue("0.01", "0.00", "1.00", "0.00", true);
        Fixture laterOrder = orders.getLast();

        job.run();

        assertThat(payoutCount(laterOrder)).isEqualTo(1);
        assertThat(orders.subList(0, 100)).allSatisfy(order -> assertThat(payoutCount(order)).isZero());
        assertThat(jdbc.queryForObject("SELECT net_amount FROM payouts WHERE order_id=?",
                BigDecimal.class, laterOrder.orderId())).isEqualByComparingTo("0.50");

        // Net bằng 0 do cấu hình không phải lỗi vĩnh viễn: đổi rate thì các đơn cũ vẫn được thử lại.
        properties.setPlatformFeeRate(BigDecimal.ZERO);
        job.run();

        assertThat(orders.subList(0, 100)).allSatisfy(order -> assertThat(payoutCount(order)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT platform_fee_rate FROM payouts WHERE order_id=?",
                BigDecimal.class, laterOrder.orderId())).isEqualByComparingTo("0.5");
    }

    @Test
    void aFailedFirstBatchDoesNotBlockALaterPageAndItsOrdersRemainRetryable() {
        List<Fixture> orders = seedQueue("100.00", "10.00", "100.00", "10.00", true);
        Set<UUID> failedIds = orders.subList(0, 100).stream().map(Fixture::orderId).collect(Collectors.toSet());
        AtomicInteger failedAttempts = new AtomicInteger();
        doAnswer(call -> {
            if (failedIds.contains(call.getArgument(0, UUID.class))) {
                failedAttempts.incrementAndGet();
                throw new IllegalStateException("Temporary payout creation failure");
            }
            return call.callRealMethod();
        }).when(payouts).createEligible(any(UUID.class));

        job.run();

        assertThat(failedAttempts).hasValue(100);
        assertThat(payoutCount(orders.getLast())).isEqualTo(1);
        assertThat(orders.subList(0, 100)).allSatisfy(order -> assertThat(payoutCount(order)).isZero());

        reset(payouts);
        job.run();
        assertThat(orders).allSatisfy(order -> assertThat(payoutCount(order)).isEqualTo(1));
    }

    @Test
    void aFullPlatformFeeDoesNotPermanentlyDiscardOrdersWhenTheRateChanges() {
        properties.setPlatformFeeRate(BigDecimal.ONE);
        List<Fixture> orders = seedQueue("100.00", "10.00", "100.00", "10.00", true);

        job.run();
        assertThat(orders).allSatisfy(order -> assertThat(payoutCount(order)).isZero());

        properties.setPlatformFeeRate(BigDecimal.ZERO);
        job.run();
        assertThat(orders).allSatisfy(order -> assertThat(payoutCount(order)).isEqualTo(1));
    }

    private List<Fixture> seedQueue(String blockedAmount, String blockedShipping,
            String validAmount, String validShipping, boolean fundedLast) {
        return transactions.execute(status -> {
            List<Fixture> orders = new ArrayList<>();
            OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).minusYears(5);
            for (int index = 0; index < 101; index++) {
                boolean last = index == 100;
                orders.add(seed(last ? validAmount : blockedAmount, last ? validShipping : blockedShipping,
                        !last || fundedLast, start.plusSeconds(index)));
            }
            return orders;
        });
    }

    private Fixture seed(String amount, String shipping, boolean funded, OffsetDateTime recordedAt) {
        UUID orderId = UUID.randomUUID(), groupId = UUID.randomUUID(), buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID(), eventId = UUID.randomUUID();
        BigDecimal paid = new BigDecimal(amount);
        String status = funded ? "PAID" : "PENDING";
        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,
                    source_event_id,idempotency_key,paid_at)
                VALUES (?,?,?,'VND',?,now()+interval '1 hour',?::payment_session_status,?,?,now()-interval '1 hour')
                RETURNING id
                """, UUID.class, groupId, buyerId, paid, groupId.toString().replace("-", "").toUpperCase(Locale.ROOT),
                status, UUID.randomUUID(), UUID.randomUUID().toString());
        UUID receiptId = jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,currency,status,type,
                    idempotency_key,received_at)
                VALUES (?,'SEPAY',?,?,'VND','SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
                """, UUID.class, sessionId, UUID.randomUUID().toString(), paid, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,
                    currency,due_at,status,paid_at,last_order_version)
                VALUES (?,?,?,?,?,?,'VND',now()+interval '1 hour',?::payment_instruction_status,now()-interval '1 hour',1)
                """, orderId, sessionId, funded ? receiptId : null, buyerId, sellerId, paid, status);
        jdbc.update("""
                INSERT INTO payment_order_completions(order_id,order_group_id,buyer_id,seller_id,order_version,
                    completed_at,order_number,source_event_id,recorded_at,order_amount,shipping_fee)
                VALUES (?,?,?,?,4,now(),?,?,?,?,?)
                """, orderId, groupId, buyerId, sellerId, "FAIR-" + orderId, eventId, recordedAt, paid,
                new BigDecimal(shipping));
        if (funded) jdbc.update("""
                INSERT INTO payout_eligibility(order_id,seller_id,buyer_id,order_group_id,funded_transaction_id,
                    amount,currency,completed_at,source_event_id)
                VALUES (?,?,?,?,?,?,'VND',now(),?)
                """, orderId, sellerId, buyerId, groupId, receiptId, paid, eventId);
        return new Fixture(orderId, sessionId, receiptId);
    }

    private void fund(Fixture order) {
        jdbc.update("UPDATE payment_sessions SET status='PAID' WHERE id=?", order.sessionId());
        jdbc.update("UPDATE payment_instructions SET status='PAID',funded_transaction_id=? WHERE order_id=?",
                order.receiptId(), order.orderId());
    }

    private long payoutCount(Fixture order) {
        return jdbc.queryForObject("SELECT count(*) FROM payouts WHERE order_id=?", Long.class, order.orderId());
    }

    private record Fixture(UUID orderId, UUID sessionId, UUID receiptId) { }
}
