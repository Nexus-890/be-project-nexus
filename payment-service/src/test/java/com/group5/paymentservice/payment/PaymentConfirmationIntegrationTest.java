package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import com.group5.paymentservice.payment.service.PaymentConfirmationService;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Kiểm tra transaction trên Flyway/PostgreSQL thật, gồm cấp tiền, outbox, hoàn tiền và rollback/retry. */
class PaymentConfirmationIntegrationTest extends PaymentDatabaseTestSupport {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private Tracer tracer;
    @Autowired private Clock clock;
    @Autowired private PaymentConfirmationService confirmation;
    @Autowired private PaymentSessionEventConsumer listener;
    private final List<Group> groups = new ArrayList<>();
    private final List<UUID> cancellationEvents = new ArrayList<>();
    private RefundFixtures fixtures;
    private MockMvc mvc;
    private Group group;

    @BeforeEach
    void setUp() {
        fixtures = new RefundFixtures(jdbc, json, tracer);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        group = seedGroup();
    }

    // Chỉ dọn constraint và dữ liệu của fixture trong ca kiểm thử, giữ nguyên dữ liệu ca khác.
    @AfterEach
    void cleanUp() {
        jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT IF EXISTS test156_reject_confirmation");
        jdbc.execute("ALTER TABLE refunds DROP CONSTRAINT IF EXISTS test156_reject_refund");
        for (Group stored : groups) {
            jdbc.update("DELETE FROM outbox WHERE aggregate_id IN (?, ?, ?)", stored.sessionId().toString(),
                    stored.firstOrder().toString(), stored.secondOrder().toString());
            jdbc.update("DELETE FROM refunds WHERE order_id IN (?, ?)", stored.firstOrder(), stored.secondOrder());
            jdbc.update("DELETE FROM payment_pending_order_cancellations WHERE order_group_id = ?", stored.id());
            jdbc.update("DELETE FROM payment_instructions WHERE session_id = ?", stored.sessionId());
            jdbc.update("DELETE FROM payment_transactions WHERE session_id = ?", stored.sessionId());
            jdbc.update("DELETE FROM payment_sessions WHERE id = ?", stored.sessionId());
        }
        for (UUID eventId : cancellationEvents) {
            jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
    }

    /** Webhook hợp lệ trả 200, cấp đủ hai phần tiền và giữ nguyên bằng chứng khi gửi lại. */
    @Test
    void signed350000ReceiptFundsBothSharesAndIdenticalDeliveryLeavesAllEvidenceUnchanged() throws Exception {
        String body = body(156001L, "350000", group.memo());
        assertAcknowledged(send(body));
        assertFunded(group, "156001");
        Map<String, Object> before = evidence(group);

        assertAcknowledged(send(body));

        assertThat(evidence(group)).isEqualTo(before);
        JsonNode envelope = confirmationEvent(group);
        assertThat(envelope.get("eventType").asString()).isEqualTo("CheckoutPaymentConfirmed");
        assertThat(envelope.get("aggregateId").asString()).isEqualTo(group.sessionId().toString());
        assertThat(envelope.get("traceId").asString()).isNotBlank();
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("sessionId").asString()).isEqualTo(group.sessionId().toString());
        assertThat(payload.get("orderGroupId").asString()).isEqualTo(group.id().toString());
        assertThat(payload.get("buyerId").asString()).isEqualTo(group.buyerId().toString());
        assertThat(payload.get("currency").asString()).isEqualTo("VND");
        assertThat(payload.get("totalAmount").asString()).isEqualTo("350000.00");
        assertThat(payload.get("sessionVersion").asLong()).isEqualTo(1);
        assertThat(Instant.parse(payload.get("paidAt").asString())).isNotNull();
        assertThat(payload.get("paidAt").asString()).endsWith("Z");
        assertThat(payload.get("transactionId").asString()).isEqualTo(transactionId(group, "156001").toString());
        assertThat(payload.get("instructions").size()).isEqualTo(2);
        Map<String, String> expected = Map.of(group.firstOrder().toString(), "200000.00",
                group.secondOrder().toString(), "150000.00");
        Map<String, String> actual = new LinkedHashMap<>();
        for (JsonNode instruction : payload.get("instructions")) {
            String orderId = instruction.get("orderId").asString();
            assertThat(UUID.fromString(instruction.get("instructionId").asString())).isEqualTo(
                    jdbc.queryForObject("SELECT id FROM payment_instructions WHERE order_id = ?",
                            UUID.class, UUID.fromString(orderId)));
            actual.put(orderId, instruction.get("amount").asString());
        }
        assertThat(actual).isEqualTo(expected);
    }

    /** Hai request khởi chạy đồng thời vẫn chỉ commit một receipt và một confirmation. */
    @Test
    void concurrentSignedDeliveriesOfOneProviderIdCommitOneReceiptAndOneConfirmation() throws Exception {
        String body = body(156002L, "350000", group.memo());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return send(body);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return send(body);
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertAcknowledged(first.get(30, TimeUnit.SECONDS));
            assertAcknowledged(second.get(30, TimeUnit.SECONDS));
        }
        assertFunded(group, "156002");
    }

    /** Bản ghi dùng key cũ vẫn được nhận diện là trùng nhờ provider và mã giao dịch. */
    @Test
    void providerIdentityStillDeduplicatesAStoredReceiptWithALegacyIdempotencyKey() throws Exception {
        String receipt = body(156020L, "350000", group.memo());
        assertAcknowledged(send(receipt));
        jdbc.update("UPDATE payment_transactions SET idempotency_key=? WHERE session_id=?",
                "legacy-receipt:" + group.sessionId(), group.sessionId());
        var before = evidence(group);

        assertAcknowledged(send(receipt));

        assertThat(evidence(group)).isEqualTo(before);
    }

    /** Sai tổng tiền chỉ lưu UNRECONCILED một lần, không cấp tiền cho bất kỳ instruction nào. */
    @Test
    void wrongAmountIsRecordedOnceAsUnreconciledWithoutFundingAnyShare() throws Exception {
        String body = body(156003L, "300000", group.memo());
        assertAcknowledged(send(body));
        assertAcknowledged(send(body));

        assertUnfunded(group, "PENDING");
        assertThat(receipts(group)).isEqualTo(1);
        assertThat(transactionStatus(group, "156003")).isEqualTo("UNRECONCILED");
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_transactions WHERE session_id = ?",
                BigDecimal.class, group.sessionId())).isEqualByComparingTo("300000.00");
    }

    /** Khoản tiền về mới sau khi đã PAID cần đối soát và không được thay receipt đã cấp tiền. */
    @Test
    void aSecondRealReceiptAfterPaymentPreservesTheOriginalFundingAndRequiresReconciliation() throws Exception {
        assertAcknowledged(send(body(156004L, "350000", group.memo())));
        UUID firstFunding = transactionId(group, "156004");
        var sessionBefore = jdbc.queryForMap("SELECT * FROM payment_sessions WHERE id = ?", group.sessionId());
        var planBefore = jdbc.queryForList("SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY order_id",
                group.sessionId());

        assertAcknowledged(send(body(156005L, "350000", group.memo())));

        assertThat(receipts(group)).isEqualTo(2);
        assertThat(transactionStatus(group, "156004")).isEqualTo("SUCCESS");
        assertThat(transactionStatus(group, "156005")).isEqualTo("UNRECONCILED");
        assertThat(confirmations(group)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_sessions WHERE id = ?", group.sessionId()))
                .isEqualTo(sessionBefore);
        assertThat(jdbc.queryForList("SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY order_id",
                group.sessionId())).isEqualTo(planBefore);
        assertThat(jdbc.queryForList("SELECT funded_transaction_id FROM payment_instructions WHERE session_id = ?",
                UUID.class, group.sessionId())).containsExactlyInAnyOrder(firstFunding, firstFunding);
    }

    /** Một mã giao dịch SePay không thể thanh toán thêm nhóm khác bằng cách đổi memo. */
    @Test
    void providerIdentityIsGlobalEvenIfADuplicateNamesAnotherGroup() throws Exception {
        Group another = seedGroup();
        assertAcknowledged(send(body(156006L, "350000", group.memo())));

        assertAcknowledged(send(body(156006L, "350000", another.memo())));

        assertFunded(group, "156006");
        assertUnfunded(another, "PENDING");
        assertThat(receipts(another)).isZero();
    }

    /** Tiền về muộn hoặc sau khi hủy không mở lại session, nhưng vẫn được lưu để đối soát. */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "EXPIRED", "PAST_DEADLINE"})
    void canceledOrLateSessionsKeepTheirStateAndRecordUnreconciledMoney(String status) throws Exception {
        if (status.equals("PAST_DEADLINE")) {
            jdbc.update("UPDATE payment_sessions SET due_at = ? WHERE id = ?",
                    OffsetDateTime.ofInstant(clock.instant().minusSeconds(1), ZoneOffset.UTC), group.sessionId());
        } else {
            jdbc.update("UPDATE payment_sessions SET status = CAST(? AS payment_session_status) WHERE id = ?",
                    status, group.sessionId());
        }

        assertAcknowledged(send(body(156007L, "350000", group.memo())));

        assertUnfunded(group, status.equals("PAST_DEADLINE") ? "PENDING" : status);
        assertThat(transactionStatus(group, "156007")).isEqualTo("UNRECONCILED");
    }

    /** Tổng session khớp chưa đủ: các instruction đã lưu cũng phải tạo thành kế hoạch nhất quán. */
    @Test
    void anInconsistentStoredPlanCannotBeFundedEvenWhenTheReceiptMatchesTheSessionTotal() throws Exception {
        jdbc.update("UPDATE payment_instructions SET amount = amount + 1 WHERE order_id = ?", group.firstOrder());

        assertAcknowledged(send(body(156008L, "350000", group.memo())));

        assertUnfunded(group, "PENDING");
        assertThat(transactionStatus(group, "156008")).isEqualTo("UNRECONCILED");
    }

    /** Dữ liệu tiền vào sai trả 400; giao dịch tiền ra được ACK nhưng không thay đổi kế hoạch. */
    @Test
    void malformedIncomingAmountsOrIdentitiesReturn400AndOutgoingMovementsChangeNothing() throws Exception {
        for (String fields : List.of(
                "\"id\":null,\"transferAmount\":350000,\"transferType\":\"in\"",
                "\"id\":0,\"transferAmount\":350000,\"transferType\":\"in\"",
                "\"id\":156009,\"transferAmount\":null,\"transferType\":\"in\"",
                "\"id\":156009,\"transferAmount\":-1,\"transferType\":\"in\"",
                "\"id\":156009,\"transferAmount\":350000.001,\"transferType\":\"in\"",
                "\"id\":156009,\"transferAmount\":100000000000000000,\"transferType\":\"in\"",
                "\"id\":156009,\"transferAmount\":350000,\"transferType\":\"other\"")) {
            MvcResult result = send("{" + fields + ",\"code\":\"" + group.memo() + "\"}");
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asString())
                    .isEqualTo("PAYMENT_INVALID_WEBHOOK_REQUEST");
        }
        assertAcknowledged(send("{\"code\":\"" + group.memo() + "\",\"transferType\":\"out\"}"));
        assertUnfunded(group, "PENDING");
        assertThat(receipts(group)).isZero();
    }

    /** Event hủy đơn có tiền đã trả đến trước được xử lý thành đúng một refund khi webhook cấp tiền. */
    @Test
    void aPaidCancellationArrivingBeforeTheWebhookGetsOneRefundInsideTheFundingTransaction() throws Exception {
        cancelBeforeFunding();
        assertThat(fixtures.pending(group.firstOrder())).isEqualTo(1);

        String body = body(156010L, "350000", group.memo());
        assertAcknowledged(send(body));
        assertAcknowledged(send(body));

        assertFunded(group, "156010");
        assertThat(fixtures.pending(group.firstOrder())).isZero();
        assertThat(fixtures.refunds(group.firstOrder())).isEqualTo(1);
        assertThat(fixtures.refunds(group.secondOrder())).isZero();
        Map<String, Object> refund = fixtures.refund(group.firstOrder());
        assertThat(refund.get("status")).isEqualTo("REQUESTED");
        assertThat(refund.get("idempotency_key")).isEqualTo("system:cancel-refund:" + group.firstOrder());
        assertThat((BigDecimal) refund.get("amount")).isEqualByComparingTo("200000.00");
        assertThat(fixtures.outbox("OrderRefundRequested", group.firstOrder())).isEqualTo(1);
        assertThat(fixtures.lastPayload("OrderRefundRequested", group.firstOrder())
                .get("incomingTransactionId").asString()).isEqualTo(transactionId(group, "156010").toString());
    }

    /** Ép lỗi ghi outbox tại DB để chứng minh receipt/funding cùng rollback và request có thể retry. */
    @Test
    void failureWritingTheConfirmationRollsBackTheReceiptAndAllFundingThenAllowsRetry() throws Exception {
        SepayWebhookRequest request = request(156011L);
        jdbc.execute("ALTER TABLE outbox ADD CONSTRAINT test156_reject_confirmation CHECK "
                + "(event_type <> 'CheckoutPaymentConfirmed' OR aggregate_id <> '" + group.sessionId() + "')");
        try {
            assertThatThrownBy(() -> fixtures.traced(() -> confirmation.confirm(group.id(), request)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnfunded(group, "PENDING");
            assertThat(receipts(group)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE outbox DROP CONSTRAINT test156_reject_confirmation");
        }

        assertAcknowledged(send(body(156011L, "350000", group.memo())));
        assertFunded(group, "156011");
    }

    /** Ép lỗi tạo refund để chứng minh confirmation rollback và yêu cầu hủy còn nguyên cho lần retry. */
    @Test
    void failureCreatingAPendingRefundRollsBackConfirmationAndRetainsTheCancellationForRetry() throws Exception {
        cancelBeforeFunding();
        jdbc.execute("ALTER TABLE refunds ADD CONSTRAINT test156_reject_refund CHECK "
                + "(order_id <> '" + group.firstOrder() + "'::uuid)");
        try {
            assertThatThrownBy(() -> fixtures.traced(() -> confirmation.confirm(group.id(), request(156012L))))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertUnfunded(group, "PENDING");
            assertThat(receipts(group)).isZero();
            assertThat(fixtures.pending(group.firstOrder())).isEqualTo(1);
            assertThat(fixtures.refunds(group.firstOrder())).isZero();
            assertThat(fixtures.outbox("OrderRefundRequested", group.firstOrder())).isZero();
        } finally {
            jdbc.execute("ALTER TABLE refunds DROP CONSTRAINT test156_reject_refund");
        }

        assertAcknowledged(send(body(156012L, "350000", group.memo())));
        assertFunded(group, "156012");
        assertThat(fixtures.pending(group.firstOrder())).isZero();
        assertThat(fixtures.refunds(group.firstOrder())).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundRequested", group.firstOrder())).isEqualTo(1);
    }

    /** Tạo riêng session 350000 và hai instruction 200000/150000 trong payment_db của test. */
    private Group seedGroup() {
        UUID groupId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String memo = "NX" + session.toString().replace("-", "").substring(0, 30).toUpperCase();
        OffsetDateTime due = OffsetDateTime.ofInstant(clock.instant().plusSeconds(900), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO payment_sessions (id, order_group_id, buyer_id, total_amount, currency, memo_code,
                                              due_at, source_event_id, idempotency_key)
                VALUES (?, ?, ?, 350000.00, 'VND', ?, ?, ?, ?)
                """, session, groupId, buyer, memo, due, UUID.randomUUID(), "test156:" + groupId);
        jdbc.update("""
                INSERT INTO payment_instructions (order_id, session_id, buyer_id, amount, currency, due_at)
                VALUES (?, ?, ?, 200000.00, 'VND', ?), (?, ?, ?, 150000.00, 'VND', ?)
                """, first, session, buyer, due, second, session, buyer, due);
        Group seeded = new Group(groupId, buyer, session, first, second, memo);
        groups.add(seeded);
        return seeded;
    }

    /** Đưa event hủy qua consumer thật để tạo yêu cầu hoàn tiền đang chờ cấp tiền. */
    private void cancelBeforeFunding() {
        var cancellation = fixtures.cancelled(group.firstOrder(), group.id(), group.buyerId(), "200000.00");
        cancellationEvents.add(cancellation.eventId());
        fixtures.traced(() -> listener.receive(fixtures.record(cancellation)));
    }

    /** Ký raw body bằng HMAC rồi gửi qua controller cùng security filter thật. */
    private MvcResult send(String body) throws Exception {
        byte[] bytes = SepayWebhookControllerTest.bytes(body);
        Instant now = clock.instant();
        String signature = SepayWebhookControllerTest.sign(bytes, now);
        // MockMvc không chạy observation filter của server nên cần chủ động tạo trace cho request.
        var span = tracer.nextSpan().name("payment-confirmation-http").start();
        try (var ignored = tracer.withSpan(span)) {
            return mvc.perform(post(SepayWebhookControllerTest.PATH).contentType(MediaType.APPLICATION_JSON)
                    .content(bytes).header("X-SePay-Signature", signature)
                    .header("X-SePay-Timestamp", now.getEpochSecond())).andReturn();
        } finally {
            span.end();
        }
    }

    private void assertAcknowledged(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(result.getResponse().getContentAsString()))
                .isEqualTo(json.readTree("{\"success\":true}"));
    }

    private void assertFunded(Group stored, String providerId) {
        assertThat(receipts(stored)).isEqualTo(1);
        assertThat(transactionStatus(stored, providerId)).isEqualTo("SUCCESS");
        assertThat(confirmations(stored)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM payment_sessions WHERE id = ?",
                String.class, stored.sessionId())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM payment_sessions WHERE id = ?",
                Boolean.class, stored.sessionId())).isTrue();
        UUID transaction = transactionId(stored, providerId);
        assertThat(jdbc.queryForList("""
                SELECT status::text AS status, funded_transaction_id, paid_at IS NOT NULL AS has_paid_at
                FROM payment_instructions WHERE session_id = ?
                """, stored.sessionId())).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PAID");
            assertThat(row.get("funded_transaction_id")).isEqualTo(transaction);
            assertThat(row.get("has_paid_at")).isEqualTo(true);
        });
        assertThat(jdbc.queryForObject("SELECT topic FROM outbox WHERE event_type = 'CheckoutPaymentConfirmed' "
                + "AND aggregate_id = ?", String.class, stored.sessionId().toString())).isEqualTo("nexus.payment.events");
    }

    private void assertUnfunded(Group stored, String sessionStatus) {
        assertThat(jdbc.queryForObject("SELECT status::text FROM payment_sessions WHERE id = ?",
                String.class, stored.sessionId())).isEqualTo(sessionStatus);
        assertThat(jdbc.queryForObject("SELECT paid_at IS NULL FROM payment_sessions WHERE id = ?",
                Boolean.class, stored.sessionId())).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT status::text AS status, funded_transaction_id, paid_at FROM payment_instructions WHERE session_id = ?
                """, stored.sessionId())).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("funded_transaction_id")).isNull();
            assertThat(row.get("paid_at")).isNull();
        });
        assertThat(confirmations(stored)).isZero();
    }

    private int receipts(Group stored) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE session_id = ?",
                Integer.class, stored.sessionId());
    }

    private int confirmations(Group stored) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type = 'CheckoutPaymentConfirmed' "
                + "AND aggregate_id = ?", Integer.class, stored.sessionId().toString());
    }

    private UUID transactionId(Group stored, String providerId) {
        return jdbc.queryForObject("SELECT id FROM payment_transactions WHERE session_id = ? AND txn_reference = ?",
                UUID.class, stored.sessionId(), providerId);
    }

    private String transactionStatus(Group stored, String providerId) {
        return jdbc.queryForObject("SELECT status::text FROM payment_transactions WHERE session_id = ? AND txn_reference = ?",
                String.class, stored.sessionId(), providerId);
    }

    private JsonNode confirmationEvent(Group stored) {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox "
                        + "WHERE event_type = 'CheckoutPaymentConfirmed' AND aggregate_id = ?",
                String.class, stored.sessionId().toString()));
    }

    /** Chụp đầy đủ dữ liệu của nhóm để phát hiện cả thay đổi audit/version không mong muốn khi replay. */
    private Map<String, Object> evidence(Group stored) {
        return Map.of("session", jdbc.queryForMap("SELECT * FROM payment_sessions WHERE id = ?", stored.sessionId()),
                "instructions", jdbc.queryForList("SELECT * FROM payment_instructions WHERE session_id = ? ORDER BY id",
                        stored.sessionId()),
                "transactions", jdbc.queryForList("SELECT * FROM payment_transactions WHERE session_id = ? ORDER BY id",
                        stored.sessionId()),
                "outbox", jdbc.queryForList("SELECT * FROM outbox WHERE aggregate_id = ? ORDER BY id",
                        stored.sessionId().toString()));
    }

    private static String body(long id, String amount, String memo) {
        return "{\"id\":" + id + ",\"code\":\"" + memo + "\",\"transferType\":\"in\",\"transferAmount\":" + amount + "}";
    }

    private SepayWebhookRequest request(long id) {
        return json.readValue(body(id, "350000", group.memo()), SepayWebhookRequest.class);
    }

    private record Group(UUID id, UUID buyerId, UUID sessionId, UUID firstOrder, UUID secondOrder, String memo) { }
}
