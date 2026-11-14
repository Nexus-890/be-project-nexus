package com.group5.paymentservice.payment;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class PaymentListApiTest extends PaymentDatabaseTestSupport {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private Tracer tracer;
    @MockitoBean private JwtDecoder decoder;
    private RefundFixtures.PaidGroup group;
    private UUID lateTransaction;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        group = new RefundFixtures(jdbc, mapper, tracer).paidGroup();
        lateTransaction = jdbc.queryForObject("""
                INSERT INTO payment_transactions (session_id, provider, txn_reference, amount, currency, status, type,
                                                  idempotency_key, received_at)
                VALUES (?, 'SEPAY', ?, 500000.00, 'VND', 'UNRECONCILED', 'NORMAL', ?, now() + interval '1 second')
                RETURNING id
                """, UUID.class, group.sessionId(), Math.abs(group.groupId().getLeastSignificantBits() % 100000) + "8877",
                "sepay:late:" + group.groupId());
        reset(decoder);
        when(decoder.decode("support")).thenReturn(token("support", UUID.randomUUID(), "SUPPORT_STAFF",
                List.of("ORDER.REFUND")));
        when(decoder.decode("owner")).thenReturn(token("owner", group.buyerId(), "BUYER", List.of("ORDER.VIEW")));
        when(decoder.decode("stranger")).thenReturn(token("stranger", UUID.randomUUID(), "BUYER",
                List.of("ORDER.VIEW")));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void supportListsUnreconciledPaymentsMasked() throws Exception {
        String body = mvc.perform(get("/api/v1/payments").param("status", "UNRECONCILED")
                        .header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andReturn().getResponse().getContentAsString();

        List<JsonNode> rows = rows(body);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("status").asString()).isEqualTo("UNRECONCILED"));
        JsonNode late = rows.stream()
                .filter(row -> row.get("transactionId").asString().equals(lateTransaction.toString()))
                .findFirst().orElseThrow();
        assertThat(late.get("providerReference").asString()).isEqualTo("****8877");
        assertThat(late.get("orderGroupId").asString()).isEqualTo(group.groupId().toString());
        assertThat(late.get("amount").decimalValue()).isEqualByComparingTo("500000.00");
        assertThat(late.has("idempotencyKey")).isFalse();
        assertThat(late.has("rawResponse")).isFalse();
    }

    @Test
    void theListRequiresOrderRefund() throws Exception {
        mvc.perform(get("/api/v1/payments").param("status", "UNRECONCILED").header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_PRIVILEGE_REQUIRED"));
    }

    @Test
    void theOwnerReadsThePaymentAttemptsOfTheirOrder() throws Exception {
        String body = mvc.perform(get("/api/v1/payments").param("orderId", group.bigOrder().toString())
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<JsonNode> rows = rows(body);
        assertThat(rows).extracting(row -> row.get("transactionId").asString())
                .containsExactly(group.transactionId().toString(), lateTransaction.toString());
        assertThat(rows).extracting(row -> row.get("status").asString()).containsExactly("SUCCESS", "UNRECONCILED");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("providerReference").asString()).startsWith("****"));
    }

    @Test
    void anotherBuyerGets403() throws Exception {
        mvc.perform(get("/api/v1/payments").param("orderId", group.bigOrder().toString())
                        .header("Authorization", "Bearer stranger"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYMENT_ACCESS_DENIED"));
    }

    @Test
    void supportReadsAnyOrdersPaymentAttempts() throws Exception {
        mvc.perform(get("/api/v1/payments").param("orderId", group.smallOrder().toString())
                        .header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void anUnknownOrderIs404() throws Exception {
        mvc.perform(get("/api/v1/payments").param("orderId", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    private List<JsonNode> rows(String body) {
        List<JsonNode> rows = new ArrayList<>();
        mapper.readTree(body).get("data").forEach(rows::add);
        return rows;
    }

    private static Jwt token(String value, UUID userId, String role, List<String> privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role)).claim("privileges", privileges).build();
    }
}
