package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.service.PaymentMatcher;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Kiểm tra webhook ghi đúng dữ liệu thanh toán và các nhánh bị bỏ qua không phát sinh tác dụng phụ. */
@Transactional
class SepayWebhookPersistenceTest extends PaymentDatabaseTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired
    PaymentMatcher matcher;
    private MockMvc mvc;
    private UUID session;

    @BeforeEach
    void prepareSessionAndInstruction() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        session = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payment_sessions
                    (id, order_group_id, buyer_id, total_amount, currency, memo_code, due_at,
                     source_event_id, idempotency_key)
                VALUES (?, ?, ?, 350000, 'VND', 'NX20261105001', now() + interval '1 hour', ?, ?)
                """, session, UUID.randomUUID(), buyer, UUID.randomUUID(), "webhook-" + session);
        jdbc.update("""
                INSERT INTO payment_instructions (order_id, session_id, buyer_id, amount, currency, due_at)
                VALUES (?, ?, ?, 350000, 'VND', now() + interval '1 hour')
                """, UUID.randomUUID(), session, buyer);
    }

    /** Memo chuẩn hóa khớp session, lần đầu ghi thanh toán còn replay giữ nguyên toàn bộ dữ liệu. */
    @Test
    void matchedDeliverySettlesOnceAndDuplicateDoesNotWriteAgain() throws Exception {
        assertThat(matcher.match(" nx20261105001 ")).isPresent();
        String receipt = "{\"id\":156001,\"code\":\" nx20261105001 \",\"transferType\":\"in\",\"transferAmount\":350000}";
        // MockMvc không cài observation filter của server nên tạo trace cho lần ghi outbox đầu tiên.
        var tracer = context.getBean(io.micrometer.tracing.Tracer.class);
        var span = tracer.nextSpan().name("webhook-persistence").start();
        try (var ignored = tracer.withSpan(span)) {
            send(receipt, true);
        } finally {
            span.end();
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM payment_sessions WHERE id=?", String.class, session))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE session_id=?", Integer.class, session))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE event_type='CheckoutPaymentConfirmed' AND aggregate_id=?",
                Integer.class, session.toString())).isEqualTo(1);
        Map<String, List<Map<String, Object>>> before = snapshot();
        send(receipt, true);
        assertThat(snapshot()).isEqualTo(before);
    }

    /** Memo không khớp hoặc chữ ký sai đều không được ghi vào các bảng thanh toán. */
    @Test
    void unmatchedAndRejectedDeliveriesLeaveEveryPaymentTableUnchanged() throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        send("{\"content\":\" NXUNKNOWN \"}", true);
        send("{\"code\":\"NX20261105001\"}", false);
        assertThat(snapshot()).isEqualTo(before);
    }

    /** Tiền chuyển ra không được thanh toán hoặc mở lại session đã hết hạn. */
    @Test
    void outgoingMovementDoesNotSettleOrReopenExpiredSession() throws Exception {
        jdbc.update("UPDATE payment_sessions SET status='EXPIRED', due_at=now()-interval '1 hour'");
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(matcher.match("NX20261105001")).isPresent();
        send("{\"code\":\"NX20261105001\",\"transferAmount\":1,\"transferType\":\"out\"}", true);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void send(String json, boolean valid) throws Exception {
        byte[] body = SepayWebhookControllerTest.bytes(json);
        Instant now = Instant.now();
        String signature = SepayWebhookControllerTest.sign(body, now);
        if (!valid) signature = signature.substring(0, signature.length() - 1)
                + (signature.endsWith("0") ? "1" : "0");
        var result = mvc.perform(post(SepayWebhookControllerTest.PATH).contentType(MediaType.APPLICATION_JSON)
                .content(body).header("X-SePay-Signature", signature).header("X-SePay-Timestamp", now.getEpochSecond()));
        if (valid) result.andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
        else result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PAYMENT_UNAUTHENTICATED"));
    }

    /** So sánh cả dữ liệu nghiệp vụ, audit và outbox để phát hiện ghi ngoài ý muốn. */
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (String table : List.of("payment_sessions", "payment_instructions", "payment_transactions", "outbox", "refunds")) {
            tables.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return tables;
    }
}
