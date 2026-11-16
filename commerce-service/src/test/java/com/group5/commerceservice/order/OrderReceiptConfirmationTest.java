package com.group5.commerceservice.order;

import com.group5.commerceservice.order.event.OrderCompleted;
import com.group5.commerceservice.order.event.OrderEventPublisher;
import com.group5.commerceservice.order.service.OrderStateMachine;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL transactions, row locks, migration validation, persisted history and outbox. */
@SpringJUnitConfig(ShipmentCompletionTest.JpaConfig.class)
class OrderReceiptConfirmationTest {
    @Autowired OrderCompletionService completions;
    @Autowired OrderCompletionJob job;
    @Autowired OrderStateMachine states;
    @Autowired OrderEventPublisher publisher;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    @Autowired Tracer tracer;

    @BeforeEach void cleanSpy() { reset(publisher); }

    @Test void deliveryPersistsOneWindowAndDoesNotPublishCompletion() {
        Fixture order = order("SHIPPED", false);
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                states.move(order.id(), OrderStatus.DELIVERED, order.buyer().id(), "Shipment delivered"));
        var dates = jdbc().queryForMap("SELECT delivered_at, auto_complete_at FROM orders WHERE id=?", order.id());
        assertThat(dates.get("delivered_at")).isNotNull();
        assertThat(jdbc().queryForObject("SELECT auto_complete_at = delivered_at + interval '7 days' FROM orders WHERE id=?", Boolean.class, order.id())).isTrue();
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(count(order, "outbox")).isZero();
        assertThat(completions.completeIfDue(order.id())).isFalse();
        assertThat(count(order, "order_status_history")).isZero();
    }

    @Test void buyerConfirmationAndReplayPersistExactlyOneHistoryAndOutbox() {
        Fixture order = order("DELIVERED", false);
        confirm(order);
        confirm(order);
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(count(order, "order_status_history")).isEqualTo(1);
        assertThat(count(order, "outbox")).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT reason FROM order_status_history WHERE order_id=? AND new_status='COMPLETED'", String.class, order.id())).isEqualTo("Buyer confirmed receipt");
        assertThat(jdbc().queryForObject("SELECT changed_by FROM order_status_history WHERE order_id=? AND new_status='COMPLETED'", UUID.class, order.id())).isEqualTo(order.buyer().id());
    }

    @Test void foreignBuyerIncludingAdminCannotConfirmInAnyStatus() {
        for (String status : List.of("DELIVERED", "COMPLETED", "PENDING", "CANCELED")) {
            Fixture order = order(status, false);
            CurrentUser foreign = new CurrentUser(UUID.randomUUID(), Set.of("BUYER", "ADMIN"), Set.of("ORDER.VIEW"));
            assertThatThrownBy(() -> completions.confirm(foreign, order.id()))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo(403);
            assertThat(status(order)).isEqualTo(status);
            assertThat(count(order, "outbox")).isZero();
        }
    }

    @Test void incompleteStatesRejectAndAuthenticationIsCheckedBeforeDatabase() {
        for (String status : List.of("PENDING", "PAID", "SHIPPED", "CANCELED", "REFUND_REQUESTED", "REFUNDED")) {
            Fixture order = order(status, false);
            assertThatThrownBy(() -> completions.confirm(order.buyer(), order.id()))
                    .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo("COMMERCE_ORDER_NOT_DELIVERED");
            assertThat(count(order, "outbox")).isZero();
        }
        assertThatThrownBy(() -> completions.confirm(null, UUID.randomUUID()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo(401);
        CurrentUser noPrivilege = new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of());
        assertThatThrownBy(() -> completions.confirm(noPrivilege, UUID.randomUUID()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo(403);
    }

    @Test void dueJobCompletesOnlyDeliveredAndWritesAutomaticReasonOnce() {
        Fixture due = order("DELIVERED", true);
        Fixture future = order("DELIVERED", false);
        Fixture shipped = order("SHIPPED", true);
        assertThat(job.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(status(due)).isEqualTo("COMPLETED");
        assertThat(status(future)).isEqualTo("DELIVERED");
        assertThat(status(shipped)).isEqualTo("SHIPPED");
        assertThat(job.runOnce()).isZero();
        assertThat(count(due, "order_status_history")).isEqualTo(1);
        assertThat(count(due, "outbox")).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT reason FROM order_status_history WHERE order_id=?", String.class, due.id())).contains("Automatically completed");
    }

    @Test void confirmationRacingSchedulerAndAnotherBuyerRequestHasOneWinner() throws Exception {
        Fixture order = order("DELIVERED", true);
        CountDownLatch ready = new CountDownLatch(3), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var futures = List.of(0, 1, 2).stream().map(index -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing start");
                traced(() -> { if (index == 0) completions.completeIfDue(order.id()); else completions.confirm(order.buyer(), order.id()); });
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var result : futures) assertThat(result.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(status(order)).isEqualTo("COMPLETED");
        assertThat(count(order, "order_status_history")).isEqualTo(1);
        assertThat(count(order, "outbox")).isEqualTo(1);
    }

    @Test void failureAfterOutboxInsertionRollsBackStatusHistoryAndOutboxThenCanRetry() {
        Fixture order = order("DELIVERED", true);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Injected after outbox"); })
                .when(publisher).orderCompleted(any(OrderCompleted.class));
        assertThatThrownBy(() -> confirm(order)).isInstanceOf(IllegalStateException.class);
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(count(order, "order_status_history")).isZero();
        assertThat(count(order, "outbox")).isZero();
        assertThat(job.runOnce()).isZero();
        assertThat(status(order)).isEqualTo("DELIVERED");
        reset(publisher);
        assertThat(job.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(count(order, "outbox")).isEqualTo(1);
    }

    @Test void reportedIssuePausesBothAutomaticAndBuyerCompletionAndRetainsOriginalReport() {
        Fixture order = order("DELIVERED", true);
        completions.reportIssue(order.buyer(), order.id(), "  One item is missing  ");
        var original = jdbc().queryForMap("SELECT receipt_issue_reason,receipt_issue_reported_at FROM orders WHERE id=?", order.id());
        completions.reportIssue(order.buyer(), order.id(), "Second submission");
        assertThat(jdbc().queryForMap("SELECT receipt_issue_reason,receipt_issue_reported_at FROM orders WHERE id=?", order.id())).isEqualTo(original);
        assertThat(original.get("receipt_issue_reason")).isEqualTo("One item is missing");
        assertThat(completions.completeIfDue(order.id())).isFalse();
        job.runOnce();
        assertThatThrownBy(() -> confirm(order)).isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("COMMERCE_RECEIPT_ISSUE_OPEN");
        assertThat(status(order)).isEqualTo("DELIVERED");
        assertThat(count(order, "order_status_history")).isZero();
        assertThat(count(order, "outbox")).isZero();
    }

    @Test void issueReportRacingTheSchedulerCannotLeaveACompletedOrderWithAnOpenHold() throws Exception {
        Fixture order = order("DELIVERED", true);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var complete = pool.submit(() -> { start.await(); traced(() -> completions.completeIfDue(order.id())); return true; });
            var report = pool.submit(() -> {
                start.await();
                try { completions.reportIssue(order.buyer(), order.id(), "Damaged item"); return "reported"; }
                catch (DomainException rejected) { assertThat(rejected.getErrorCode()).isEqualTo("COMMERCE_ORDER_NOT_DELIVERED"); return "already completed"; }
            });
            start.countDown();
            assertThat(complete.get(20, TimeUnit.SECONDS)).isTrue();
            String result = report.get(20, TimeUnit.SECONDS);
            if (result.equals("reported")) {
                assertThat(status(order)).isEqualTo("DELIVERED");
                assertThat(count(order, "outbox")).isZero();
            } else {
                assertThat(status(order)).isEqualTo("COMPLETED");
                assertThat(count(order, "outbox")).isEqualTo(1);
                assertThat(jdbc().queryForObject("SELECT receipt_issue_reported_at IS NULL FROM orders WHERE id=?", Boolean.class, order.id())).isTrue();
            }
        }
    }

    private void confirm(Fixture order) { traced(() -> completions.confirm(order.buyer(), order.id())); }
    private void traced(Runnable action) {
        var span = tracer.nextSpan().name("receipt-test").start();
        try (var ignored = tracer.withSpan(span)) { action.run(); } finally { span.end(); }
    }
    private String status(Fixture order) { return jdbc().queryForObject("SELECT status::text FROM orders WHERE id=?", String.class, order.id()); }
    private long count(Fixture order, String table) {
        String query = table.equals("outbox") ? "SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='OrderCompleted'"
                : "SELECT count(*) FROM order_status_history WHERE order_id=?::uuid AND new_status='COMPLETED'";
        return jdbc().queryForObject(query, Long.class, order.id().toString());
    }
    private JdbcTemplate jdbc() { return new JdbcTemplate(source); }
    private Fixture order(String status, boolean due) {
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        UUID group = jdbc().queryForObject("INSERT INTO order_groups (buyer_id,idempotency_key,total_amount,due_at) VALUES (?,?,100,now()+interval '15 minutes') RETURNING id", UUID.class, buyer, UUID.randomUUID().toString());
        UUID id = jdbc().queryForObject("""
                INSERT INTO orders (order_group_id,user_id,seller_id,order_number,status,order_type,subtotal,total_amount,idempotency_key,
                                    delivered_at,auto_complete_at)
                VALUES (?,?,?,?,CAST(? AS order_status),'STANDARD',100,100,?,now()-interval '8 days',?) RETURNING id
                """, UUID.class, group, buyer, seller, "NX-RECEIPT-" + UUID.randomUUID().toString().substring(0, 20), status,
                UUID.randomUUID().toString(), OffsetDateTime.now().plusDays(due ? -1 : 7));
        if (status.equals("SHIPPED") && !due) jdbc().update("UPDATE orders SET delivered_at=null,auto_complete_at=null WHERE id=?", id);
        return new Fixture(id, new CurrentUser(buyer, Set.of("BUYER"), Set.of("ORDER.VIEW")));
    }
    private record Fixture(UUID id, CurrentUser buyer) { }
}
