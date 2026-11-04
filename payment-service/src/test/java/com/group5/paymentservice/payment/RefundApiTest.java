package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class RefundApiTest extends PaymentDatabaseTestSupport {
    private static final UUID SUPPORT = UUID.randomUUID();

    @Autowired private WebApplicationContext context;
    @Autowired private PaymentSessionEventConsumer listener;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private Tracer tracer;
    @MockitoBean private JwtDecoder decoder;
    private RefundFixtures fixtures;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        fixtures = new RefundFixtures(jdbc, mapper, tracer);
        reset(decoder);
        when(decoder.decode("support")).thenReturn(token("support", SUPPORT, List.of("ORDER.REFUND")));
        when(decoder.decode("buyer")).thenReturn(token("buyer", UUID.randomUUID(), List.of("ORDER.VIEW")));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void theSameKeyAndBodyTwiceReturnsTheSameRefundAndWritesOnce() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String body = body(orderId, null, "Seller out of stock");

        String first = refund("key-ac1", body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        String refundId = mapper.readTree(first).get("data").get("refundId").asString();
        refund("key-ac1", body).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.refundId").value(refundId));

        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundRequested", orderId)).isEqualTo(1);
        assertThat((BigDecimal) fixtures.refund(orderId).get("amount")).isEqualByComparingTo(RefundFixtures.BIG);
        assertThat(fixtures.refund(orderId).get("requested_by")).isEqualTo(SUPPORT);
        assertThat(fixtures.lastPayload("OrderRefundRequested", orderId).get("actorId").asString())
                .isEqualTo(SUPPORT.toString());
    }

    @Test
    void theSameKeyWithAnotherReasonIsAConflict() throws Exception {
        UUID orderId = cancelledPaidOrder();
        refund("key-conflict", body(orderId, null, "Seller out of stock")).andExpect(status().isAccepted());

        refund("key-conflict", body(orderId, null, "Something else")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_IDEMPOTENCY_CONFLICT"));
        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
    }

    @Test
    void moreThanThePaidAmountIsRejectedAndNothingIsStored() throws Exception {
        UUID orderId = cancelledPaidOrder();

        refund("key-ac2", body(orderId, "300000.01", "Too much")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_AMOUNT_EXCEEDED"));
        assertThat(fixtures.refunds(orderId)).isZero();
        assertThat(fixtures.outbox("OrderRefundRequested", orderId)).isZero();
    }

    @Test
    void aPartialAmountIsStoredExactly() throws Exception {
        UUID orderId = cancelledPaidOrder();

        refund("key-partial", body(orderId, "120000.50", "Partial goodwill")).andExpect(status().isAccepted());
        assertThat((BigDecimal) fixtures.refund(orderId).get("amount")).isEqualByComparingTo("120000.50");
    }

    @Test
    void aPaidOrderThatPaymentHasNotSeenCancelledIsNotRefundable() throws Exception {
        var group = fixtures.paidGroup();

        refund("key-not-cancelled", body(group.bigOrder(), null, "Early")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_REFUNDABLE"));
    }

    @Test
    void anUnknownOrderIsNotFound() throws Exception {
        refund("key-unknown", body(UUID.randomUUID(), null, "Nothing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_ORDER_NOT_FOUND"));
    }

    @Test
    void aSecondKeyForAnOrderThatAlreadyHasARefundIsAConflict() throws Exception {
        var group = fixtures.paidGroup();
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG))));

        refund("key-second", body(group.bigOrder(), null, "Again")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EXISTS"));
    }

    @Test
    void missingOrSystemKeysAreRejected() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String body = body(orderId, null, "Seller out of stock");

        perform(post("/api/v1/refunds").header("Authorization", "Bearer support")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_REFUND_REQUEST"));
        refund("system:cancel-refund:" + orderId, body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_REFUND_REQUEST"));
        assertThat(fixtures.refunds(orderId)).isZero();
    }

    @Test
    void aBlankReasonFailsValidation() throws Exception {
        refund("key-blank", body(UUID.randomUUID(), null, " ")).andExpect(status().isBadRequest());
    }

    @Test
    void withoutOrderRefundTheRequestIsForbidden() throws Exception {
        UUID orderId = cancelledPaidOrder();

        perform(post("/api/v1/refunds").header("Authorization", "Bearer buyer").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(body(orderId, null, "x")))
                .andExpect(status().isForbidden());
        perform(get("/api/v1/refunds").param("orderId", orderId.toString()).header("Authorization", "Bearer buyer"))
                .andExpect(status().isForbidden());
        perform(get("/api/v1/refunds").param("orderId", orderId.toString())).andExpect(status().isUnauthorized());
        assertThat(fixtures.refunds(orderId)).isZero();
    }

    @Test
    void listingByOrderReturnsTheRefundWithAPage() throws Exception {
        UUID orderId = cancelledPaidOrder();
        refund("key-list", body(orderId, null, "Seller out of stock")).andExpect(status().isAccepted());

        perform(get("/api/v1/refunds").param("orderId", orderId.toString()).header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    private UUID cancelledPaidOrder() {
        var group = fixtures.paidGroup();
        jdbc.update("UPDATE payment_instructions SET canceled_at = now() WHERE order_id = ?", group.bigOrder());
        return group.bigOrder();
    }

    private ResultActions refund(String key, String body) throws Exception {
        return perform(post("/api/v1/refunds").header("Authorization", "Bearer support")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        try {
            return fixtures.traced(() -> {
                try {
                    return mvc.perform(request);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } catch (IllegalStateException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            throw wrapped;
        }
    }

    private static String body(UUID orderId, String amount, String reason) {
        return "{\"orderId\":\"" + orderId + "\"," + (amount == null ? "" : "\"amount\":" + amount + ",")
                + "\"reason\":\"" + reason + "\"}";
    }

    private static Jwt token(String value, UUID userId, List<String> privileges) {
        return Jwt.withTokenValue(value).header("alg", "RS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("SUPPORT_STAFF")).claim("privileges", privileges).build();
    }
}
