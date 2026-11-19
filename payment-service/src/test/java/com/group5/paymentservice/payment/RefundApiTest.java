package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.event.PaymentSessionEventConsumer;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

class RefundApiTest extends PaymentDatabaseTestSupport {
    private static final UUID SUPPORT = UUID.randomUUID();
    private static final byte[] PROOF =
            "%PDF-1.4\n% synthetic refund proof\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private WebApplicationContext context;
    @Autowired private PaymentSessionEventConsumer listener;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper mapper;
    @Autowired private Tracer tracer;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private PayoutEvidenceStorage storage;
    private final Map<String, PayoutEvidenceStorage.StoredEvidence> files = new ConcurrentHashMap<>();
    private RefundFixtures fixtures;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        fixtures = new RefundFixtures(jdbc, mapper, tracer);
        reset(decoder);
        when(decoder.decode("support")).thenReturn(token("support", SUPPORT, List.of("ORDER.REFUND")));
        when(decoder.decode("buyer")).thenReturn(token("buyer", UUID.randomUUID(), List.of("ORDER.VIEW")));
        files.clear();
        when(storage.configured()).thenReturn(true);
        doAnswer(call -> {
            files.put(call.getArgument(0), new PayoutEvidenceStorage.StoredEvidence(call.getArgument(1),
                    call.getArgument(2)));
            return null;
        }).when(storage).put(anyString(), any(), anyString());
        doAnswer(call -> files.remove(call.<String>getArgument(0))).when(storage).delete(anyString());
        when(storage.get(anyString())).thenAnswer(call -> files.get(call.<String>getArgument(0)));
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
        // Email gửi chủ instruction đã lưu, không gửi Support đang thao tác.
        assertThat(fixtures.lastPayload("OrderRefundRequested", orderId).get("buyerId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT buyer_id FROM payment_instructions WHERE order_id = ?",
                        UUID.class, orderId).toString()).isNotEqualTo(SUPPORT.toString());
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
    void aPartialAmountThenTheSameKeyWithoutAmountIsAConflict() throws Exception {
        UUID orderId = cancelledPaidOrder();
        refund("key-partial-then-full", body(orderId, "120000.50", "Partial goodwill")).andExpect(status().isAccepted());

        refund("key-partial-then-full", body(orderId, null, "Partial goodwill")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_IDEMPOTENCY_CONFLICT"));
        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
        assertThat((BigDecimal) fixtures.refund(orderId).get("amount")).isEqualByComparingTo("120000.50");
    }

    @Test
    void noAmountThenTheFullAmountWithTheSameKeyIsTheSameRefund() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-default-then-full");

        refund("key-default-then-full", body(orderId, RefundFixtures.BIG, "Seller out of stock"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.refundId").value(refundId));
        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
    }

    @Test
    void theFullAmountThenNoAmountWithTheSameKeyIsTheSameRefund() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requestedWith(orderId, "key-full-then-default", RefundFixtures.BIG, "Seller out of stock");

        refund("key-full-then-default", body(orderId, null, "Seller out of stock"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.refundId").value(refundId));
        assertThat(fixtures.refunds(orderId)).isEqualTo(1);
    }

    @Test
    void theSameAmountWithAnotherScaleIsTheSameRefund() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requestedWith(orderId, "key-scale", "100.0", "Partial goodwill");

        refund("key-scale", body(orderId, "100.00", "Partial goodwill"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.refundId").value(refundId));
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
    void aCancelledPaidOrderIsRefundedAutomaticallyAndSupportCompletesThatRefund() throws Exception {
        var group = fixtures.paidGroup();
        fixtures.traced(() -> listener.receive(fixtures.record(
                fixtures.cancelled(group.bigOrder(), group.groupId(), group.buyerId(), RefundFixtures.BIG))));
        String refundId = fixtures.refund(group.bigOrder()).get("id").toString();

        refund("key-manual", body(group.bigOrder(), "1000.00", "Partial")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EXISTS"));
        perform(get("/api/v1/refunds").param("orderId", group.bigOrder().toString())
                .header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refundId").value(refundId))
                .andExpect(jsonPath("$.data[0].status").value("REQUESTED"));
        evidence(refundId, "FT-AUTO-1", PROOF).andExpect(status().isOk());
        complete(refundId, "support").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertThat(fixtures.refunds(group.bigOrder())).isEqualTo(1);
        assertThat(fixtures.refund(group.bigOrder()).get("completed_by")).isEqualTo(SUPPORT);
        assertThat(fixtures.outbox("OrderRefundRequested", group.bigOrder())).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundCompleted", group.bigOrder())).isEqualTo(1);
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

    @Test
    void completingARefundRecordsOneRefundTransactionAndOneEvent() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-complete");
        evidence(refundId, "FT-COMPLETE", PROOF).andExpect(status().isOk());

        complete(refundId, "support").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty());

        var stored = fixtures.refund(orderId);
        assertThat(stored.get("completed_by")).isEqualTo(SUPPORT);
        var transfer = jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id = ?",
                stored.get("completed_transaction_id"));
        assertThat(transfer.get("type")).hasToString("REFUND");
        assertThat(transfer.get("status")).hasToString("SUCCESS");
        assertThat(transfer.get("provider")).isEqualTo("MANUAL_TRANSFER");
        assertThat((BigDecimal) transfer.get("amount")).isEqualByComparingTo(RefundFixtures.BIG);
        assertThat(fixtures.outbox("OrderRefundCompleted", orderId)).isEqualTo(1);
        var payload = fixtures.lastPayload("OrderRefundCompleted", orderId);
        assertThat(payload.get("refundVersion").asLong()).isEqualTo(1);
        assertThat(payload.get("completedTransactionId").asString())
                .isEqualTo(stored.get("completed_transaction_id").toString());
        assertThat(payload.get("actorId").asString()).isEqualTo(SUPPORT.toString());
        // Sự kiện hoàn tất vẫn giữ đúng người mua của đơn được hoàn tiền.
        assertThat(payload.get("buyerId").asString())
                .isEqualTo(jdbc.queryForObject("SELECT buyer_id FROM payment_instructions WHERE order_id = ?",
                        UUID.class, orderId).toString());
    }

    @Test
    void completingTwiceReturnsTheSameRefundAndWritesOnce() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-twice");
        evidence(refundId, "FT-TWICE", PROOF).andExpect(status().isOk());

        complete(refundId, "support").andExpect(status().isOk());
        complete(refundId, "support").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE idempotency_key = ?",
                Long.class, "refund:" + refundId)).isEqualTo(1);
        assertThat(fixtures.outbox("OrderRefundCompleted", orderId)).isEqualTo(1);
    }

    @Test
    void twoCompletionsAtTheSameTimeBothReturnTheRefundAndWriteOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID orderId = cancelledPaidOrder();
                String refundId = requested(orderId, "key-race-" + round);
                evidence(refundId, "FT-RACE-" + round, PROOF).andExpect(status().isOk());
                CyclicBarrier start = new CyclicBarrier(2);
                Callable<MvcResult> click = () -> {
                    start.await(5, TimeUnit.SECONDS);
                    return complete(refundId, "support").andReturn();
                };

                for (Future<MvcResult> response : pool.invokeAll(List.of(click, click))) {
                    MvcResult result = response.get(30, TimeUnit.SECONDS);
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    var data = mapper.readTree(result.getResponse().getContentAsString()).get("data");
                    assertThat(data.get("refundId").asString()).isEqualTo(refundId);
                    assertThat(data.get("status").asString()).isEqualTo("COMPLETED");
                }
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM payment_transactions transfer
                        JOIN payment_instructions instruction ON instruction.session_id = transfer.session_id
                        WHERE instruction.order_id = ? AND transfer.type = 'REFUND'
                        """, Long.class, orderId)).isEqualTo(1);
                assertThat(fixtures.outbox("OrderRefundCompleted", orderId)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completingAnUnknownRefundIsNotFound() throws Exception {
        complete(UUID.randomUUID().toString(), "support").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_NOT_FOUND"));
    }

    @Test
    void withoutOrderRefundCompletingIsForbidden() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-buyer-complete");

        complete(refundId, "buyer").andExpect(status().isForbidden());
        assertThat(fixtures.refund(orderId).get("status")).hasToString("REQUESTED");
        assertThat(fixtures.outbox("OrderRefundCompleted", orderId)).isZero();
    }

    @Test
    void completingWithoutEvidenceIsRejectedAndWritesNothing() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-no-evidence");

        complete(refundId, "support").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EVIDENCE_REQUIRED"));
        assertThat(fixtures.refund(orderId).get("status")).hasToString("REQUESTED");
        assertThat(fixtures.outbox("OrderRefundCompleted", orderId)).isZero();
    }

    @Test
    void evidenceIsStoredPrivatelyListedAndDownloadable() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-evidence");

        String response = evidence(refundId, "  FT2611190001  ", PROOF).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data.evidence[0].bankReference").value("FT2611190001"))
                .andExpect(jsonPath("$.data.evidence[0].sizeBytes").value(PROOF.length))
                .andExpect(jsonPath("$.data.evidence[0].uploadedBy").value(SUPPORT.toString()))
                .andReturn().getResponse().getContentAsString();
        String evidenceId = mapper.readTree(response).get("data").get("evidence").get(0).get("evidenceId").asString();

        var row = jdbc.queryForMap("SELECT * FROM refund_evidence WHERE id = ?::uuid", evidenceId);
        assertThat(row.get("object_key")).isEqualTo("refunds/" + refundId + "/" + evidenceId + ".pdf");
        assertThat(files).containsOnlyKeys("refunds/" + refundId + "/" + evidenceId + ".pdf");
        perform(get("/api/v1/refunds").param("orderId", orderId.toString()).header("Authorization", "Bearer support"))
                .andExpect(jsonPath("$.data[0].evidence[0].evidenceId").value(evidenceId));
        perform(get("/api/v1/refunds/" + refundId + "/evidence/" + evidenceId).header("Authorization", "Bearer support"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PROOF));
        perform(get("/api/v1/refunds/" + refundId + "/evidence/" + UUID.randomUUID())
                .header("Authorization", "Bearer support"))
                .andExpect(status().isNotFound());
        perform(get("/api/v1/refunds/" + refundId + "/evidence/" + evidenceId).header("Authorization", "Bearer buyer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSameFileTwiceKeepsOneRowAndNoOrphan() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-evidence-replay");

        evidence(refundId, "FT-1", PROOF).andExpect(status().isOk());
        evidence(refundId, "FT-1 again", PROOF).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].bankReference").value("FT-1"));
        assertThat(files).hasSize(1);
    }

    @Test
    void aFourthFileIsRejectedAndNothingIsLeftInStorage() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-evidence-limit");
        for (int n = 1; n <= 3; n++) {
            evidence(refundId, "FT-" + n, proof(n)).andExpect(status().isOk());
        }

        evidence(refundId, "FT-4", proof(4)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EVIDENCE_LIMIT"));
        assertThat(files).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_evidence WHERE refund_id = ?::uuid",
                Long.class, refundId)).isEqualTo(3);
    }

    @Test
    void aBlankBankReferenceOrABadFileOrACompletedRefundIsRejected() throws Exception {
        UUID orderId = cancelledPaidOrder();
        String refundId = requested(orderId, "key-evidence-bad");

        evidence(refundId, "   ", PROOF).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EVIDENCE_INVALID"));
        evidence(refundId, "x".repeat(101), PROOF).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_EVIDENCE_INVALID"));
        evidence(refundId, "FT-TXT", "plain text, not a receipt".getBytes(StandardCharsets.US_ASCII))
                .andExpect(status().isUnsupportedMediaType());
        evidence(UUID.randomUUID().toString(), "FT-404", PROOF).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_NOT_FOUND"));
        perform(multipart("/api/v1/refunds/" + refundId + "/evidence")
                .file(new MockMultipartFile("file", "proof.pdf", "application/pdf", PROOF))
                .param("bankReference", "FT-BUYER").header("Authorization", "Bearer buyer"))
                .andExpect(status().isForbidden());

        evidence(refundId, "FT-DONE", PROOF).andExpect(status().isOk());
        complete(refundId, "support").andExpect(status().isOk());
        evidence(refundId, "FT-LATE", proof(9)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_REFUND_COMPLETED"));
        assertThat(files).hasSize(1);
    }

    private static byte[] proof(int n) {
        return ("%PDF-1.4\n% synthetic refund proof " + n + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private ResultActions evidence(String refundId, String bankReference, byte[] bytes) throws Exception {
        return perform(multipart("/api/v1/refunds/" + refundId + "/evidence")
                .file(new MockMultipartFile("file", "proof.pdf", "application/pdf", bytes))
                .param("bankReference", bankReference)
                .header("Authorization", "Bearer support"));
    }

    private String requested(UUID orderId, String key) throws Exception {
        return requestedWith(orderId, key, null, "Seller out of stock");
    }

    private String requestedWith(UUID orderId, String key, String amount, String reason) throws Exception {
        String response = refund(key, body(orderId, amount, reason))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("data").get("refundId").asString();
    }

    private ResultActions complete(String refundId, String token) throws Exception {
        return perform(post("/api/v1/refunds/" + refundId + "/complete").header("Authorization", "Bearer " + token));
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

    private ResultActions perform(RequestBuilder request) throws Exception {
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
