package com.group5.paymentservice.payout.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.group5.common_events.EventEnvelope;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full JWT → Payment HTTP/controller/service → PostgreSQL, with only storage/provider external
 * boundaries mocked.
 */
@TestPropertySource(
        properties = {
            "nexus.payout.execution-mode=MANUAL",
            "nexus.payout.manual.enabled=true",
            "nexus.payout.jobs-enabled=false",
            "nexus.payout.manual.expiry-cron=-",
            "nexus.payout.manual.evidence.cleanup-enabled=false",
            "nexus.payout.manual.source-accounts[0].id=platform-main",
            "nexus.payout.manual.source-accounts[0].alias=Platform HTTP integration source",
            "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class ManualPayoutPostgresHttpTest extends PaymentDatabaseTestSupport {
    private static final String BASE = "/api/v1/payments/manual-payouts";
    private static final String KEY_ID = "manual-postgres-http-test";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer JWKS = jwksServer();
    private static final byte[] PROOF =
            "%PDF-1.4\n% synthetic payment test proof\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired BankAccountService banks;
    @Autowired PayoutEligibilityService completions;
    @Autowired ManualPayoutProperties settings;
    @Autowired PayoutProperties payoutSettings;
    @MockitoBean PayoutEvidenceStorage storage;
    @MockitoBean PayoutGateway gateway;
    private final CurrentUser admin = actor("ADMIN"), support = actor("SUPPORT_STAFF");
    private final Map<String, PayoutEvidenceStorage.StoredEvidence> files =
            new ConcurrentHashMap<>();
    private MockMvc mvc;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add(
                "nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stopKeys() {
        JWKS.stop(0);
    }

    @BeforeEach
    void setup() {
        settings.setEnabled(true);
        settings.setQrDeadline(Duration.ofMinutes(10));
        settings.setClaimLease(Duration.ofMinutes(5));
        payoutSettings.setExecutionMode("MANUAL");
        payoutSettings.setJobsEnabled(false);
        files.clear();
        when(storage.configured()).thenReturn(true);
        doAnswer(
                        call -> {
                            files.put(
                                    call.getArgument(0),
                                    new PayoutEvidenceStorage.StoredEvidence(
                                            call.getArgument(1), call.getArgument(2)));
                            return null;
                        })
                .when(storage)
                .put(anyString(), any(), anyString());
        when(storage.get(anyString())).thenAnswer(call -> files.get(call.getArgument(0)));
        mvc =
                webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    @AfterEach
    void noAutomaticBankActivity() {
        verify(gateway, never()).send(any());
        verify(gateway, never()).lookup(anyString());
    }

    @Test
    void supportCompletesTheHttpWorkflowAndSellerReceiptNeverAddsASecondPosting() throws Exception {
        Fixture f = funded();
        mvc.perform(
                        get(BASE)
                                .param("sellerId", f.seller().id().toString())
                                .header(HttpHeaders.AUTHORIZATION, bearer(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(f.payout().toString()))
                .andExpect(jsonPath("$.data[0].netAmount").value("95000.00"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        JsonNode initial = detail(f, support);
        String claimKey = key();
        Map<String, Object> claimBody =
                Map.of("expectedPayoutVersion", initial.get("version").asLong());
        JsonNode claimed = postData(detailPath(f) + "/claims", support, claimKey, claimBody, 201);
        JsonNode claimReplay =
                postData(detailPath(f) + "/claims", support, claimKey, claimBody, 200);
        assertThat(claimReplay).isEqualTo(claimed);
        JsonNode issued = issue(f, claimed, support);
        String attempt = issued.get("currentAttempt").get("id").asString();
        byte[] qr =
                mvc.perform(
                                get(attemptPath(f, attempt) + "/qr")
                                        .header(HttpHeaders.AUTHORIZATION, bearer(support)))
                        .andExpect(status().isOk())
                        .andExpect(content().contentType(MediaType.IMAGE_PNG))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        assertThat(qr).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
        JsonNode ready = upload(f, issued, support);
        String evidence = ready.get("evidence").get(0).get("id").asString();
        mvc.perform(
                        get(attemptPath(f, attempt) + "/evidence/" + evidence)
                                .header(HttpHeaders.AUTHORIZATION, bearer(support)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PROOF))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"));
        String confirmationKey = key();
        Map<String, Object> confirmation = confirmation(ready);
        JsonNode settled =
                postData(
                        attemptPath(f, attempt) + "/confirmation",
                        support,
                        confirmationKey,
                        confirmation,
                        200);
        assertThat(settled.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(settled.get("confirmationSource").asString())
                .isEqualTo("MANUAL_STAFF_CONFIRMED");
        assertThat(settled.get("currentAttempt").get("receiptStatus").asString())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(
                        postData(
                                attemptPath(f, attempt) + "/confirmation",
                                support,
                                confirmationKey,
                                confirmation,
                                200))
                .isEqualTo(settled);
        String receiptKey = key();
        Map<String, Object> receiptBody =
                Map.of(
                        "expectedReceiptVersion",
                        settled.get("currentAttempt").get("receiptVersion").asLong());
        JsonNode received =
                postData(
                        sellerPath(f, attempt) + "/receipt-confirmations",
                        f.seller(),
                        receiptKey,
                        receiptBody,
                        200);
        assertThat(received.get("receiptStatus").asString()).isEqualTo("CONFIRMED");
        assertThat(
                        postData(
                                sellerPath(f, attempt) + "/receipt-confirmations",
                                f.seller(),
                                receiptKey,
                                receiptBody,
                                200))
                .isEqualTo(received);
        String ownerDetail =
                mvc.perform(
                                get("/api/v1/payments/payouts/" + f.payout())
                                        .header(HttpHeaders.AUTHORIZATION, bearer(f.seller())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.manual.receiptStatus").value("CONFIRMED"))
                        .andExpect(jsonPath("$.data.manual.attemptId").value(attempt))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(ownerDetail)
                .doesNotContain(
                        "object_key", "account_encrypted", "sourceAccountId", "Platform HTTP");
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(f, "PAYOUT_RETURN")).isZero();
        assertThat(movements(f, "PAYOUT_CORRECTION")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM manual_payout_attempts WHERE payout_id=?",
                                Long.class,
                                f.payout()))
                .isEqualTo(1);
    }

    @Test
    void actualHttpOwnershipBlocksOtherSellerStaffSelfPaymentAndAdminQrBypass() throws Exception {
        Fixture f = funded();
        JsonNode ready = upload(f, issue(f, claim(f, support), support), support);
        String attempt = ready.get("currentAttempt").get("id").asString(),
                evidence = ready.get("evidence").get(0).get("id").asString();
        mvc.perform(
                        get(attemptPath(f, attempt) + "/qr")
                                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_MANUAL_PAYOUT_CONFLICT"));
        mvc.perform(
                        get(attemptPath(f, attempt) + "/evidence/" + evidence)
                                .header(HttpHeaders.AUTHORIZATION, bearer(actor("SUPPORT_STAFF"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(attemptPath(f, attempt) + "/evidence/" + evidence)
                                .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isConflict());
        JsonNode settled =
                postData(
                        attemptPath(f, attempt) + "/confirmation",
                        support,
                        key(),
                        confirmation(ready),
                        200);
        Map<String, Object> receipt =
                Map.of(
                        "expectedReceiptVersion",
                        settled.get("currentAttempt").get("receiptVersion").asLong());
        postData(
                sellerPath(f, attempt) + "/receipt-confirmations",
                actor("SELLER"),
                key(),
                receipt,
                404);
        var self = new CurrentUser(f.seller().id(), Set.of("ADMIN", "SELLER"), Set.of());
        Fixture own = funded(f.seller());
        postData(
                detailPath(own) + "/claims",
                self,
                key(),
                Map.of("expectedPayoutVersion", detail(own, admin).get("version").asLong()),
                403);
        mvc.perform(
                        get("/api/v1/payments/payouts/" + f.payout())
                                .header(HttpHeaders.AUTHORIZATION, bearer(actor("SELLER"))))
                .andExpect(status().isNotFound());
        assertThat(movements(f, "PAYOUT")).isEqualTo(1);
        assertThat(movements(own, "PAYOUT")).isZero();
    }

    @Test
    void expiredIssuanceRequiresAnAdminResolutionThroughTheActualHttpAndDatabaseBoundary()
            throws Exception {
        Fixture f = funded();
        JsonNode claimed = claim(f, support), issued;
        settings.setQrDeadline(Duration.ofNanos(1));
        try {
            issued = issue(f, claimed, support);
        } finally {
            settings.setQrDeadline(Duration.ofMinutes(10));
        }
        JsonNode ready = upload(f, issued, support);
        String attempt = ready.get("currentAttempt").get("id").asString();
        postData(
                attemptPath(f, attempt) + "/confirmation",
                support,
                key(),
                confirmation(ready),
                409);
        JsonNode uncertain = detail(f, admin);
        assertThat(uncertain.get("status").asString()).isEqualTo("UNKNOWN");
        JsonNode review = uncertain.get("cases").get(0);
        Map<String, Object> resolution =
                Map.of(
                        "expectedPayoutVersion",
                        uncertain.get("version").asLong(),
                        "expectedAttemptVersion",
                        uncertain.get("currentAttempt").get("version").asLong(),
                        "expectedCaseVersion",
                        review.get("version").asLong(),
                        "caseId",
                        review.get("id").asString(),
                        "outcome",
                        "CONFIRMED_NOT_SENT",
                        "reason",
                        "Admin reviewed synthetic proof of non-execution",
                        "evidenceIds",
                        List.of(uncertain.get("evidence").get(0).get("id").asString()));
        postData(attemptPath(f, attempt) + "/resolution", support, key(), resolution, 403);
        JsonNode resolved =
                postData(attemptPath(f, attempt) + "/resolution", admin, key(), resolution, 200);
        assertThat(resolved.get("status").asString()).isEqualTo("FAILED");
        assertThat(resolved.get("currentAttempt").get("state").asString()).isEqualTo("NOT_SENT");
        assertThat(resolved.get("cases").get(0).get("status").asString()).isEqualTo("RESOLVED");
        assertThat(movements(f, "PAYOUT")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payouts WHERE order_id=?",
                                Long.class,
                                f.order()))
                .isEqualTo(1);
    }

    private JsonNode claim(Fixture f, CurrentUser owner) throws Exception {
        return postData(
                detailPath(f) + "/claims",
                owner,
                key(),
                Map.of("expectedPayoutVersion", detail(f, owner).get("version").asLong()),
                201);
    }

    private JsonNode issue(Fixture f, JsonNode claimed, CurrentUser owner) throws Exception {
        JsonNode a = claimed.get("currentAttempt");
        return postData(
                attemptPath(f, a.get("id").asString()) + "/qr-issuances",
                owner,
                key(),
                Map.of("expectedAttemptVersion", a.get("version").asLong()),
                201);
    }

    private JsonNode upload(Fixture f, JsonNode issued, CurrentUser owner) throws Exception {
        JsonNode a = issued.get("currentAttempt");
        var response =
                mvc.perform(
                                multipart(attemptPath(f, a.get("id").asString()) + "/evidence")
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        "synthetic-proof.pdf",
                                                        "application/pdf",
                                                        PROOF))
                                        .param(
                                                "expectedAttemptVersion",
                                                Long.toString(a.get("version").asLong()))
                                        .header("Idempotency-Key", key())
                                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse();
        return mapper.readTree(response.getContentAsString()).get("data");
    }

    private Map<String, Object> confirmation(JsonNode ready) {
        return Map.of(
                "expectedAttemptVersion",
                ready.get("currentAttempt").get("version").asLong(),
                "sourceAccountId",
                "platform-main",
                "amount",
                "95000.00",
                "bankTransferReference",
                "HTTP" + key(),
                "transferredAt",
                OffsetDateTime.now().toString(),
                "evidenceIds",
                List.of(ready.get("evidence").get(0).get("id").asString()));
    }

    private JsonNode detail(Fixture f, CurrentUser reader) throws Exception {
        String response =
                mvc.perform(get(detailPath(f)).header(HttpHeaders.AUTHORIZATION, bearer(reader)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return mapper.readTree(response).get("data");
    }

    private JsonNode postData(String path, CurrentUser actor, String key, Object body, int status)
            throws Exception {
        String response =
                mvc.perform(
                                post(path)
                                        .header(HttpHeaders.AUTHORIZATION, bearer(actor))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(body)))
                        .andExpect(status().is(status))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        JsonNode parsed = mapper.readTree(response);
        return status < 400 ? parsed.get("data") : parsed;
    }

    private long movements(Fixture f, String type) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND type::text=?",
                Long.class,
                f.session(),
                type);
    }

    private static String detailPath(Fixture f) {
        return BASE + "/" + f.payout();
    }

    private static String attemptPath(Fixture f, String attempt) {
        return detailPath(f) + "/attempts/" + attempt;
    }

    private static String sellerPath(Fixture f, String attempt) {
        return "/api/v1/payments/payouts/" + f.payout() + "/manual-attempts/" + attempt;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static CurrentUser actor(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of());
    }

    private Fixture funded() {
        return funded(actor("SELLER"));
    }

    private Fixture funded(CurrentUser seller) {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        UUID session =
                jdbc.queryForObject(
                        """
INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,source_event_id,idempotency_key,paid_at)
VALUES (?,?,100000,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour') RETURNING id
""",
                        UUID.class,
                        group,
                        buyer,
                        group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                        UUID.randomUUID(),
                        key());
        UUID funding =
                jdbc.queryForObject(
                        """
INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)
VALUES (?,'SEPAY',?,100000,'SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
""",
                        UUID.class,
                        session,
                        key(),
                        key());
        jdbc.update(
                """
INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,amount,due_at,status,paid_at,last_order_version)
VALUES (?,?,?,?,?,100000,now()+interval '1 hour','PAID',now()-interval '1 hour',1)
""",
                order,
                session,
                funding,
                buyer,
                seller.id());
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM seller_bank WHERE seller_id=? AND active AND"
                            + " is_default AND verification_status='VERIFIED'",
                        Long.class,
                        seller.id())
                == 0) {
            var bank =
                    banks.create(
                            seller,
                            new BankAccountRequest(
                                    "970436", "000000001234", "HTTP TEST SELLER", true, true));
            banks.verify(
                    admin,
                    bank.id(),
                    new BankVerificationRequest("VERIFIED", null, bank.version()));
        }
        Instant completed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        completions.completed(
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCompleted",
                        order.toString(),
                        completed,
                        "manual-postgres-http-test",
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
        return new Fixture(
                jdbc.queryForObject("SELECT id FROM payouts WHERE order_id=?", UUID.class, order),
                order,
                session,
                seller);
    }

    private static String bearer(CurrentUser actor) {
        try {
            Instant now = Instant.now();
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer("user-service")
                            .audience("nexus-services")
                            .subject(actor.id().toString())
                            .claim("roles", List.copyOf(actor.roles()))
                            .claim("privileges", List.of())
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(600)))
                            .build();
            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                            claims);
            jwt.sign(new RSASSASigner(KEY));
            return "Bearer " + jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext(
                    "/jwks",
                    exchange -> {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(UUID payout, UUID order, UUID session, CurrentUser seller) {}
}
