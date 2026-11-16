package com.group5.paymentservice.payout;

import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = {"nexus.payment.payout-eligibility.cron=-", "nexus.payout.reconcile-interval=PT10M"})
class PayoutReconciliationFairnessTest extends PaymentDatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PayoutDispatchService dispatcher;
    @Autowired PayoutDispatchRepository dispatches;
    @Autowired TransactionTemplate transactions;
    @MockitoBean PayoutGateway gateway;
    private String provider;

    @BeforeEach
    void isolateProvider() {
        provider = "fair-" + UUID.randomUUID().toString().substring(0, 12);
        when(gateway.provider()).thenReturn(provider);
        when(gateway.simulated()).thenReturn(false);
    }

    @Test
    void oneHundredUnresolvedOrFailedLookupsCannotStarveTheNextConfirmedPayout() {
        List<UUID> payouts = attempts(101);
        UUID last = payouts.getLast();
        Map<String, AtomicInteger> lookups = new ConcurrentHashMap<>();
        when(gateway.lookup(anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            lookups.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            UUID id = UUID.fromString(key.substring("payout:".length(), key.lastIndexOf(':')));
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT next_reconcile_at>now() FROM payouts WHERE id=?", Boolean.class, id)).isTrue();
            if (id.equals(last)) return new PayoutGateway.Result(PayoutGateway.State.SUCCEEDED, "confirmed-" + id, "SUCCESS");
            int index = payouts.indexOf(id);
            if (index % 3 == 0) throw new IllegalStateException("lookup unavailable");
            return new PayoutGateway.Result(index % 3 == 1 ? PayoutGateway.State.UNKNOWN : PayoutGateway.State.ACCEPTED,
                    null, "PENDING");
        });

        dispatcher.reconcile();
        assertThat(lookups).hasSize(100);
        assertThat(lookups).doesNotContainKey(PayoutService.key(last, 1));
        dispatcher.reconcile();

        assertThat(lookups).hasSize(101);
        assertThat(lookups.values()).allMatch(count -> count.get() == 1);
        assertThat(status(last)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE provider=? AND type='PAYOUT'",
                Integer.class, provider)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payouts WHERE provider=? AND attempt_count<>1",
                Integer.class, provider)).isZero();
        verify(gateway, never()).send(any());
    }

    @Test
    void concurrentWorkersClaimDifferentRowsAndCannotReclaimAnActiveLease() throws Exception {
        List<UUID> ids = attempts(2);
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        CountDownLatch claimed = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transactions.execute(status -> {
                var result = dispatches.claimNextReconciliation(provider, now.minusSeconds(10), now.plusMinutes(5));
                claimed.countDown();
                await(claimed);
                return result.orElseThrow();
            }));
            var second = pool.submit(() -> transactions.execute(status -> {
                var result = dispatches.claimNextReconciliation(provider, now.minusSeconds(10), now.plusMinutes(5));
                claimed.countDown();
                await(claimed);
                return result.orElseThrow();
            }));
            var one = first.get(20, TimeUnit.SECONDS);
            var two = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of((UUID) one.get("id"), (UUID) two.get("id"))).containsExactlyInAnyOrderElementsOf(ids);
        }
        var additionalClaim = transactions.execute(status -> dispatches.claimNextReconciliation(provider,
                now.minusSeconds(10), now.plusMinutes(5)));
        assertThat(additionalClaim).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payouts WHERE provider=? AND next_reconcile_at>now()",
                Integer.class, provider)).isEqualTo(2);
    }

    @Test
    void expiredLeaseAfterWorkerCrashRecoversWithoutChangingTheAttemptStartTime() {
        UUID id = attempts(1).getFirst();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var startedAt = jdbc.queryForObject("SELECT attempt_started_at FROM payouts WHERE id=?", OffsetDateTime.class, id);
        var claimed = transactions.execute(status -> dispatches.claimNextReconciliation(provider,
                now.minusSeconds(10), now.plusMinutes(5)));
        assertThat(claimed).isPresent();
        when(gateway.lookup(anyString())).thenReturn(new PayoutGateway.Result(PayoutGateway.State.UNKNOWN, null, "PENDING"));

        // Mô phỏng worker dừng sau khi claim: chưa hết lease thì worker khác không được lookup lại.
        dispatcher.reconcile();
        verify(gateway, never()).lookup(anyString());
        jdbc.update("UPDATE payouts SET next_reconcile_at=now()-interval '1 second' WHERE id=?", id);
        dispatcher.reconcile();
        dispatcher.reconcile();

        verify(gateway, times(1)).lookup(PayoutService.key(id, 1));
        assertThat(jdbc.queryForObject("SELECT attempt_started_at FROM payouts WHERE id=?", OffsetDateTime.class, id))
                .isEqualTo(startedAt);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM payouts WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE provider=?", Integer.class, provider)).isZero();
        assertThat(jdbc.queryForObject("SELECT next_reconcile_at>now() FROM payouts WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test
    void newlyStartedAttemptStillWaitsForTheProviderTimeoutBeforeFirstLookup() {
        UUID id = attempts(1).getFirst();
        jdbc.update("UPDATE payouts SET attempt_started_at=now(),status='PROCESSING' WHERE id=?", id);
        dispatcher.reconcile();
        verify(gateway, never()).lookup(anyString());
        jdbc.update("UPDATE payouts SET attempt_started_at=now()-interval '1 hour' WHERE id=?", id);
        when(gateway.lookup(anyString())).thenReturn(new PayoutGateway.Result(PayoutGateway.State.ACCEPTED, null, "PENDING"));
        dispatcher.reconcile();
        verify(gateway).lookup(PayoutService.key(id, 1));
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT next_reconcile_at>now() FROM payouts WHERE id=?", Boolean.class, id)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Second worker did not claim a row");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM payouts WHERE id=?", String.class, id);
    }

    private List<UUID> attempts(int count) {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        UUID session = jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,
                    source_event_id,idempotency_key,paid_at)
                VALUES (?,?,?,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '2 hours') RETURNING id
                """, UUID.class, group, buyer, count * 100,
                group.toString().replace("-", "").toUpperCase(Locale.ROOT), UUID.randomUUID(), UUID.randomUUID().toString());
        UUID funding = jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)
                VALUES (?,'SEPAY',?,?,'SUCCESS','NORMAL',?,now()-interval '2 hours') RETURNING id
                """, UUID.class, session, UUID.randomUUID().toString(), count * 100, UUID.randomUUID().toString());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID order = UUID.randomUUID();
            UUID instruction = jdbc.queryForObject("""
                    INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,
                        amount,due_at,status,paid_at)
                    VALUES (?,?,?,?,?,100,now()+interval '1 hour','PAID',now()-interval '2 hours') RETURNING id
                    """, UUID.class, order, session, funding, buyer, seller);
            UUID payout = jdbc.queryForObject("""
                    INSERT INTO payouts(order_id,session_id,instruction_id,seller_id,order_amount,shipping_fee,
                        platform_fee_rate,platform_fee,net_amount,currency,status,attempt_count,attempt_started_at,provider,source_event_id)
                    VALUES (?,?,?,?,100,10,0,0,90,'VND',?::payout_status,1,?, ?,?) RETURNING id
                    """, UUID.class, order, session, instruction, seller, i % 2 == 0 ? "UNKNOWN" : "PROCESSING",
                    OffsetDateTime.now(ZoneOffset.UTC).minusHours(1).plusSeconds(i), provider, UUID.randomUUID());
            ids.add(payout);
        }
        return ids;
    }
}
