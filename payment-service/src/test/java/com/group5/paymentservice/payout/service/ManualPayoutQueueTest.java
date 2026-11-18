package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.group5.common_events.EventEnvelope;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.request.ManualPayoutFilter;
import com.group5.paymentservice.payout.dto.response.ManualPayoutPage;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import javax.sql.DataSource;

/**
 * Counts SQL prepared at the real JDBC connection boundary, rather than mocking repository calls.
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
@Import(ManualPayoutQueueTest.CaptureConfiguration.class)
class ManualPayoutQueueTest extends PaymentDatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ManualPayoutQueryService queries;
    @Autowired ManualPayoutService commands;
    @Autowired BankAccountService banks;
    @Autowired PayoutEligibilityService completions;
    private final CurrentUser admin = actor("ADMIN"), support = actor("SUPPORT_STAFF");

    @Test
    void aHundredRowsUseTheSameThreeSqlStatementsAsOneAndNeverReadHistoryOrProof() {
        var seller = actor("SELLER");
        verifiedBank(seller);
        for (int row = 0; row < 100; row++) funded(seller);
        var one = recordQueue(seller, 1);
        var hundred = recordQueue(seller, 100);
        assertThat(one.page().content()).hasSize(1);
        assertThat(hundred.page().content()).hasSize(100);
        assertThat(hundred.page().page().totalElements()).isEqualTo(100);
        assertThat(one.sql()).hasSize(3);
        assertThat(hundred.sql()).hasSize(3);
        assertThat(hundred.sql())
                .noneMatch(
                        sql ->
                                sql.contains("payout_history")
                                        || sql.contains("manual_payout_evidence"));
        assertThat(hundred.page().content())
                .allSatisfy(
                        row -> {
                            assertThat(row.history()).isEmpty();
                            assertThat(row.evidence()).isEmpty();
                            assertThat(row.blockedReason()).isNull();
                            assertThat(row.allowedActions()).contains("CLAIM");
                            assertThat(row.recipient().accountLast4()).isEqualTo("1234");
                        });
        var detail = queries.detail(admin, hundred.page().content().getFirst().id());
        assertThat(detail.history()).isNotEmpty();
    }

    @Test
    void queueActionsUseOnlyCurrentAttemptCaseKindsAndMaskCaseDetails() {
        var seller = actor("SELLER");
        verifiedBank(seller);
        UUID payout = funded(seller);
        var p = queries.detail(admin, payout);
        var claimed =
                commands.execute(
                                support,
                                payout,
                                null,
                                null,
                                "CLAIM",
                                key(),
                                new ClaimCommand(p.version()))
                        .result();
        UUID attempt = claimed.currentAttempt().id();
        var issued =
                commands.execute(
                                support,
                                payout,
                                attempt,
                                null,
                                "ISSUE_QR",
                                key(),
                                new AttemptVersionCommand(claimed.currentAttempt().version(), null))
                        .result();
        var reported =
                commands.execute(
                                support,
                                payout,
                                attempt,
                                null,
                                "REPORT_PROBLEM",
                                key(),
                                new ProblemCommand(
                                        issued.currentAttempt().version(),
                                        "Private executor explanation",
                                        List.of()))
                        .result();
        var onlyStaff = queue(seller, 100).content().getFirst();
        assertThat(onlyStaff.allowedActions()).contains("RESOLVE").doesNotContain("REVIEW_DISPUTE");
        assertThat(onlyStaff.currentAttempt()).isEqualTo(reported.currentAttempt());
        assertThat(onlyStaff.cases())
                .hasSize(1)
                .allSatisfy(review -> assertThat(review.explanation()).isEmpty());
        assertThat(queries.detail(admin, payout).cases().getFirst().explanation())
                .isEqualTo("Private executor explanation");

        UUID dispute = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " payout_review_cases(id,payout_id,attempt_id,kind,status,reason,explanation,reported_by)"
                    + " VALUES (?,?,?,'SELLER_DISPUTE','OPEN','NOT_RECEIVED','Seller review"
                    + " request',?)",
                dispute,
                payout,
                attempt,
                seller.id());
        assertThat(queue(seller, 100).content().getFirst().allowedActions())
                .contains("REVIEW_DISPUTE");
        jdbc.update(
                "UPDATE payout_review_cases SET status='RESOLVED',version=version+1 WHERE id=?",
                dispute);
        assertThat(queue(seller, 100).content().getFirst().allowedActions())
                .doesNotContain("REVIEW_DISPUTE");
    }

    @Test
    void queuePreloadedReadinessMatchesDetailAfterFundingAndBankChangesWithoutWriting() {
        var seller = actor("SELLER");
        verifiedBank(seller);
        UUID payout = funded(seller);
        jdbc.update(
                "UPDATE payment_instructions SET canceled_at=now() WHERE order_id=(SELECT order_id"
                        + " FROM payouts WHERE id=?)",
                payout);
        var before =
                jdbc.queryForMap(
                        "SELECT version,updated_at,status FROM payouts WHERE id=?", payout);
        var summary = queue(seller, 100).content().getFirst();
        assertThat(summary.blockedReason()).isEqualTo("FUNDING_OR_REFUND_NOT_READY");
        assertThat(summary.allowedActions()).doesNotContain("CLAIM").contains("READINESS_CHECK");
        assertThat(summary.blockedReason())
                .isEqualTo(queries.detail(admin, payout).blockedReason());
        jdbc.update(
                "UPDATE payment_instructions SET canceled_at=NULL WHERE order_id=(SELECT order_id"
                        + " FROM payouts WHERE id=?)",
                payout);
        jdbc.update(
                "UPDATE seller_bank SET active=false,is_default=false,version=version+1 WHERE"
                        + " seller_id=?",
                seller.id());
        summary = queue(seller, 100).content().getFirst();
        assertThat(summary.blockedReason()).isEqualTo("BANK_NOT_READY");
        assertThat(summary.recipient()).isNull();
        assertThat(summary.blockedReason())
                .isEqualTo(queries.detail(admin, payout).blockedReason());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT version,updated_at,status FROM payouts WHERE id=?", payout))
                .isEqualTo(before);
    }

    private QueueCapture recordQueue(CurrentUser seller, int size) {
        List<String> sql = new ArrayList<>();
        CaptureConfiguration.CAPTURE.set(sql);
        try {
            return new QueueCapture(queue(seller, size), List.copyOf(sql));
        } finally {
            CaptureConfiguration.CAPTURE.remove();
        }
    }

    private ManualPayoutPage queue(CurrentUser seller, int size) {
        return queries.queue(
                admin,
                new ManualPayoutFilter(
                        seller.id(), List.of(), null, null, null, null, null, null, 0, size));
    }

    private void verifiedBank(CurrentUser seller) {
        var bank =
                banks.create(
                        seller,
                        new BankAccountRequest(
                                "970436", "000000001234", "QUEUE TEST SELLER", true, true));
        banks.verify(
                admin, bank.id(), new BankVerificationRequest("VERIFIED", null, bank.version()));
    }

    private UUID funded(CurrentUser seller) {
        UUID order = UUID.randomUUID(), group = UUID.randomUUID(), buyer = UUID.randomUUID();
        UUID session =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,source_event_id,idempotency_key,paid_at)"
                            + " VALUES (?,?,100000,'VND',?,now()+interval '1"
                            + " hour','PAID',?,?,now()-interval '1 hour') RETURNING id",
                        UUID.class,
                        group,
                        buyer,
                        group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                        UUID.randomUUID(),
                        key());
        UUID funding =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)"
                            + " VALUES (?,'SEPAY',?,100000,'SUCCESS','NORMAL',?,now()-interval '1"
                            + " hour') RETURNING id",
                        UUID.class,
                        session,
                        key(),
                        key());
        jdbc.update(
                "INSERT INTO"
                    + " payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,due_at,status,paid_at,last_order_version)"
                    + " VALUES (?,?,?,?,?,100000,now()+interval '1 hour','PAID',now()-interval '1"
                    + " hour',1)",
                order,
                session,
                funding,
                buyer,
                seller.id());
        Instant completed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        completions.completed(
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCompleted",
                        order.toString(),
                        completed,
                        "queue-test",
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
        return jdbc.queryForObject("SELECT id FROM payouts WHERE order_id=?", UUID.class, order);
    }

    private static CurrentUser actor(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private record QueueCapture(ManualPayoutPage page, List<String> sql) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        static final ThreadLocal<List<String>> CAPTURE = new ThreadLocal<>();

        @Bean
        static BeanPostProcessor captureStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            return tracked(super.getConnection());
                        }

                        @Override
                        public Connection getConnection(String user, String password)
                                throws SQLException {
                            return tracked(super.getConnection(user, password));
                        }
                    };
                }
            };
        }

        private static Connection tracked(Connection connection) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) -> {
                                var capture = CAPTURE.get();
                                if (capture != null
                                        && method.getName().equals("prepareStatement")
                                        && args != null
                                        && args[0] instanceof String sql) capture.add(sql);
                                try {
                                    return method.invoke(connection, args);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }
    }
}
