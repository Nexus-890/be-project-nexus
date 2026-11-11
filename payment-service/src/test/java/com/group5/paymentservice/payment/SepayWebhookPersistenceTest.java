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

@Transactional
class SepayWebhookPersistenceTest extends PaymentDatabaseTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired
    PaymentMatcher matcher;
    private MockMvc mvc;

    @BeforeEach
    void prepareSessionAndInstruction() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        UUID session = UUID.randomUUID();
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

    @Test
    void matchedDeliveryAndDuplicatePerformNoFinancialOrAuditWrites() throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThat(matcher.match(" nx20261105001 ")).isPresent();
        send("{\"code\":\" nx20261105001 \",\"transferAmount\":350000}", true);
        send("{\"code\":\" nx20261105001 \",\"transferAmount\":350000}", true);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void unmatchedAndRejectedDeliveriesLeaveEveryPaymentTableUnchanged() throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        send("{\"content\":\" NXUNKNOWN \"}", true);
        send("{\"code\":\"NX20261105001\"}", false);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void thisStageMatchesEvenExpiredSessionWithoutSettlingOrReopeningIt() throws Exception {
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

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (String table : List.of("payment_sessions", "payment_instructions", "payment_transactions", "outbox", "refunds")) {
            tables.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return tables;
    }
}
