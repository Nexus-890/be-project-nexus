package com.group5.paymentservice.payout.controller;

import static com.group5.paymentservice.payout.evidence.PayoutEvidenceErrorCode.*;
import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.PageInfo;
import com.group5.common_events.autoconfigure.EventsAutoConfiguration;
import com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.*;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage.StoredEvidence;
import com.group5.paymentservice.payout.service.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.WebApplicationContext;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Real RS256/JWKS and controller method security; domain behavior is covered by PostgreSQL service
 * tests.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = ManualPayoutHttpTest.TestApplication.class)
@TestPropertySource(
        properties = {
            "spring.config.name=manual-payout-http-test",
            "spring.config.import=",
            "spring.cloud.config.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "spring.cloud.compatibility-verifier.enabled=false",
            "eureka.client.enabled=false",
            "management.tracing.export.zipkin.enabled=false",
            "nexus.security.jwt.issuer=user-service",
            "nexus.security.jwt.audience=nexus-services"
        })
class ManualPayoutHttpTest {
    private static final UUID PAYOUT = UUID.randomUUID(),
            ATTEMPT = UUID.randomUUID(),
            EVIDENCE = UUID.randomUUID(),
            CASE = UUID.randomUUID(),
            ACTOR = UUID.randomUUID(),
            SELLER = UUID.randomUUID();
    private static final String BASE = "/api/v1/payments/manual-payouts";
    private static final String DETAIL = BASE + "/" + PAYOUT;
    private static final String ATTEMPT_PATH = DETAIL + "/attempts/" + ATTEMPT;
    private static final String SELLER_PATH =
            "/api/v1/payments/payouts/" + PAYOUT + "/manual-attempts/" + ATTEMPT;
    private static final String KEY_ID = "manual-payout-http-test";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer JWKS = jwksServer();
    @Autowired WebApplicationContext context;
    @Autowired ManualPayoutService commands;
    @Autowired ManualPayoutQueryService queries;
    @Autowired SellerPayoutReceiptService receipts;
    @Autowired PayoutDisputeService disputes;
    private MockMvc mvc;
    private ManualPayoutView view;
    private ManualCommandResult result;
    private ManualReceiptView receipt;

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
        reset(commands, queries, receipts, disputes);
        mvc =
                webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var attempt =
                new ManualPayoutView.Attempt(
                        ATTEMPT,
                        1,
                        "QR_ISSUED",
                        1,
                        ACTOR,
                        now.plusMinutes(5),
                        now.plusMinutes(10),
                        "NXP01234567890123456789",
                        "AWAITING_CONFIRMATION",
                        0,
                        null,
                        null);
        view =
                new ManualPayoutView(
                        PAYOUT,
                        UUID.randomUUID(),
                        SELLER,
                        "PROCESSING",
                        "MANUAL",
                        null,
                        "100000.00",
                        "5000.00",
                        "0.00",
                        "95000.00",
                        "VND",
                        2,
                        null,
                        List.of("READ_QR", "UPLOAD_EVIDENCE", "CONFIRM_TRANSFER"),
                        now,
                        now,
                        now,
                        attempt,
                        List.of(new ManualPayoutView.Evidence(EVIDENCE, "image/png", 12, now)),
                        List.of(),
                        List.of());
        result = new ManualCommandResult(view, false, false, false);
        receipt =
                new ManualReceiptView(
                        ATTEMPT, "SUCCEEDED", "CONFIRMED", 1, false, List.of("OPEN_DISPUTE"));
        when(queries.capabilities(any()))
                .thenReturn(
                        new ManualPayoutCapabilities(
                                true, "MANUAL", false, now, List.of(), List.of("EXECUTE")));
        when(queries.queue(any(), any()))
                .thenReturn(new ManualPayoutPage(List.of(view), new PageInfo(0, 20, 1, 1)));
        when(queries.detail(any(), eq(PAYOUT))).thenReturn(view);
        when(commands.execute(
                        any(),
                        any(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(result);
        when(commands.upload(any(), any(), any(), anyString(), anyLong(), any()))
                .thenReturn(result);
        when(receipts.confirm(any(), any(), any(), anyString(), any())).thenReturn(result);
        when(disputes.open(any(), any(), any(), anyString(), any())).thenReturn(result);
        when(receipts.projection(any())).thenReturn(receipt);
    }

    @Test
    void manualControllerRequiresRealStaffRoleEvenWithForgedPrivileges() throws Exception {
        for (Call call : staffCalls()) {
            mvc.perform(call.request()).andExpect(status().isUnauthorized());
            for (List<String> roles :
                    List.of(List.<String>of(), List.of("BUYER"), List.of("SELLER"))) {
                mvc.perform(call.request().header(HttpHeaders.AUTHORIZATION, bearer(roles, false)))
                        .andExpect(status().isForbidden())
                        .andExpect(
                                content()
                                        .contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON));
            }
            mvc.perform(
                            call.request()
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            bearer(List.of("ADMIN"), true)))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(commands, queries, receipts, disputes);
    }

    @Test
    void adminAndSupportReachExecutionCommandsWithTheJwtActor() throws Exception {
        for (String role : List.of("ADMIN", "SUPPORT_STAFF")) {
            for (Call call : staffCalls()) {
                mvc.perform(
                                call.request()
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(List.of(role), false)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0));
            }
        }
        ArgumentCaptor<CurrentUser> actors = ArgumentCaptor.forClass(CurrentUser.class);
        verify(commands, atLeastOnce())
                .execute(
                        actors.capture(),
                        any(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        anyString(),
                        anyString(),
                        any());
        assertThat(actors.getAllValues())
                .allSatisfy(
                        actor -> {
                            assertThat(actor.id()).isEqualTo(ACTOR);
                            assertThat(actor.roles())
                                    .anyMatch(
                                            role ->
                                                    role.equals("ADMIN")
                                                            || role.equals("SUPPORT_STAFF"));
                        });
    }

    @Test
    void supportCannotResolveReviewOrRetryEvenTheirOwnTransfer() throws Exception {
        for (Call call : adminCalls()) {
            for (List<String> roles :
                    List.of(
                            List.of("SUPPORT_STAFF"),
                            List.of("SUPPORT_STAFF", "SELLER"),
                            List.of("SELLER"))) {
                mvc.perform(call.request().header(HttpHeaders.AUTHORIZATION, bearer(roles, false)))
                        .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(commands, queries, receipts, disputes);
        for (Call call : adminCalls()) {
            mvc.perform(
                            call.request()
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            bearer(List.of("ADMIN"), false)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void sellerReceiptRoutesRequireSellerIdentityAndAllowMixedRoleOwnersToReachOwnershipChecks()
            throws Exception {
        var calls =
                List.of(
                        new Call(
                                "POST",
                                SELLER_PATH + "/receipt-confirmations",
                                "{\"expectedReceiptVersion\":0}"),
                        new Call(
                                "POST",
                                SELLER_PATH + "/receipt-disputes",
                                "{\"expectedReceiptVersion\":0,\"reason\":\"NOT_RECEIVED\",\"explanation\":\"Please"
                                    + " review this transfer\"}"));
        for (Call call : calls) {
            mvc.perform(call.request()).andExpect(status().isUnauthorized());
            for (String role : List.of("ADMIN", "SUPPORT_STAFF", "BUYER")) {
                mvc.perform(
                                call.request()
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(List.of(role), false)))
                        .andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(commands, queries, receipts, disputes);
        for (Call call : calls) {
            for (List<String> roles :
                    List.of(
                            List.of("SELLER"),
                            List.of("SELLER", "ADMIN"),
                            List.of("SELLER", "SUPPORT_STAFF"))) {
                String response =
                        mvc.perform(
                                        call.request()
                                                .header(
                                                        HttpHeaders.AUTHORIZATION,
                                                        bearer(roles, false)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT.toString()))
                                .andExpect(jsonPath("$.data.receiptStatus").value("CONFIRMED"))
                                .andExpect(jsonPath("$.data.evidence").doesNotExist())
                                .andExpect(jsonPath("$.data.history").doesNotExist())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                assertThat(response)
                        .doesNotContain(
                                "claimedBy",
                                "bankTransferReference",
                                "objectKey",
                                "sourceAccountId");
            }
        }
    }

    @Test
    void invalidCommandBodiesAndMissingIdempotencyHeaderAreRejectedBeforeTheDomainService()
            throws Exception {
        String auth = bearer(List.of("ADMIN"), false);
        mvc.perform(
                        post(DETAIL + "/claims")
                                .header(HttpHeaders.AUTHORIZATION, auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedPayoutVersion\":0}"))
                .andExpect(status().isBadRequest());
        for (String body :
                List.of(
                        "{}",
                        "{\"expectedPayoutVersion\":-1}",
                        "{\"expectedPayoutVersion\":\"bad\"}")) {
            mvc.perform(
                            post(DETAIL + "/claims")
                                    .header(HttpHeaders.AUTHORIZATION, auth)
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PAYMENT_MANUAL_PAYOUT_INVALID_REQUEST"));
        }
        for (String body :
                List.of(
                        "{\"expectedReceiptVersion\":-1,\"reason\":\"NOT_RECEIVED\",\"explanation\":\"review\"}",
                        "{\"expectedReceiptVersion\":0,\"reason\":\"UNKNOWN_REASON\",\"explanation\":\"review\"}",
                        "{\"expectedReceiptVersion\":0,\"reason\":\"NOT_RECEIVED\",\"explanation\":\""
                            + "   \"}")) {
            mvc.perform(
                            post(SELLER_PATH + "/receipt-disputes")
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            bearer(List.of("SELLER"), false))
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(commands, queries, receipts, disputes);
    }

    @Test
    void commandCreationAndReplayUse201And200WithTheSameAuthoritativeView() throws Exception {
        String key = UUID.randomUUID().toString();
        when(commands.execute(any(), eq(PAYOUT), isNull(), isNull(), eq("CLAIM"), eq(key), any()))
                .thenReturn(
                        new ManualCommandResult(view, false, true, false),
                        new ManualCommandResult(view, true, true, false));
        String auth = bearer(List.of("ADMIN"), false);
        var first =
                mvc.perform(
                                post(DETAIL + "/claims")
                                        .header(HttpHeaders.AUTHORIZATION, auth)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"expectedPayoutVersion\":0}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        var replay =
                mvc.perform(
                                post(DETAIL + "/claims")
                                        .header(HttpHeaders.AUTHORIZATION, auth)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"expectedPayoutVersion\":0}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void persistedReconciliationConflictIsAnHttp409WithRecoveryVersion() throws Exception {
        when(commands.execute(
                        any(),
                        any(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        anyString(),
                        anyString(),
                        any()))
                .thenReturn(new ManualCommandResult(view, false, false, true));
        mvc.perform(
                        new Call(
                                        "POST",
                                        ATTEMPT_PATH + "/qr-issuances",
                                        "{\"expectedAttemptVersion\":0}")
                                .request()
                                .header(HttpHeaders.AUTHORIZATION, bearer(List.of("ADMIN"), false)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_MANUAL_PAYOUT_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.allowedActions").isArray());
    }

    @Test
    void privateBinaryReadsPreserveHeadersAndDependencyErrorsStayProblemDetails() throws Exception {
        byte[] png = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        when(commands.qr(any(), eq(PAYOUT), eq(ATTEMPT))).thenReturn(png);
        when(commands.evidence(any(), eq(PAYOUT), eq(ATTEMPT), eq(EVIDENCE)))
                .thenReturn(new StoredEvidence(png, "image/png"));
        String auth = bearer(List.of("SUPPORT_STAFF"), false);
        for (String path : List.of(ATTEMPT_PATH + "/qr", ATTEMPT_PATH + "/evidence/" + EVIDENCE)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(
                            get(path)
                                    .header(
                                            HttpHeaders.AUTHORIZATION,
                                            bearer(List.of("SELLER"), false)))
                    .andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(content().bytes(png))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        when(commands.evidence(any(), eq(PAYOUT), eq(ATTEMPT), eq(EVIDENCE)))
                .thenThrow(new DomainException(EVIDENCE_UNAVAILABLE));
        mvc.perform(
                        get(ATTEMPT_PATH + "/evidence/" + EVIDENCE)
                                .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_MANUAL_PAYOUT_EVIDENCE_UNAVAILABLE"));
        verify(commands, never())
                .execute(any(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void multipartBindingAndEvidenceErrorsUseTheirDocumentedHttpStatuses() throws Exception {
        String path = ATTEMPT_PATH + "/evidence", auth = bearer(List.of("ADMIN"), false);
        mvc.perform(
                        multipart(path)
                                .param("expectedAttemptVersion", "1")
                                .header(HttpHeaders.AUTHORIZATION, auth)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        multipart(path)
                                .file(file())
                                .header(HttpHeaders.AUTHORIZATION, auth)
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(commands);
        for (var error : List.of(EVIDENCE_TOO_LARGE, EVIDENCE_UNSUPPORTED, EVIDENCE_UNAVAILABLE)) {
            doThrow(new DomainException(error))
                    .when(commands)
                    .upload(any(), any(), any(), anyString(), anyLong(), any());
            mvc.perform(
                            multipart(path)
                                    .file(file())
                                    .param("expectedAttemptVersion", "1")
                                    .header(HttpHeaders.AUTHORIZATION, auth)
                                    .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().is(error.status().value()))
                    .andExpect(
                            content()
                                    .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    @Test
    void databaseAndIdempotencyErrorsKeepTheirStableCodesWithoutExposingSqlOrSecrets()
            throws Exception {
        String sensitive =
                "recipient_account_encrypted bank-account-000001234 supabase-secret SQL DETAIL";
        var errors =
                List.<RuntimeException>of(
                        new DataIntegrityViolationException(sensitive),
                        new DataAccessResourceFailureException(sensitive),
                        new DomainException(MANUAL_PAYOUT_IDEMPOTENCY_CONFLICT));
        int[] statuses = {409, 503, 409};
        String[] codes = {
            "PAYMENT_MANUAL_PAYOUT_CONFLICT",
            "PAYMENT_MANUAL_PAYOUT_UNAVAILABLE",
            "PAYMENT_MANUAL_PAYOUT_IDEMPOTENCY_CONFLICT"
        };
        for (int i = 0; i < errors.size(); i++) {
            doThrow(errors.get(i))
                    .when(commands)
                    .execute(
                            any(),
                            any(),
                            nullable(UUID.class),
                            nullable(UUID.class),
                            anyString(),
                            anyString(),
                            any());
            String body =
                    mvc.perform(
                                    new Call(
                                                    "POST",
                                                    DETAIL + "/claims",
                                                    "{\"expectedPayoutVersion\":0}")
                                            .request()
                                            .header(
                                                    HttpHeaders.AUTHORIZATION,
                                                    bearer(List.of("ADMIN"), false)))
                            .andExpect(status().is(statuses[i]))
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value(codes[i]))
                            .andExpect(
                                    header().string(HttpHeaders.CACHE_CONTROL, "no-store, private"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(body)
                    .doesNotContain(
                            "recipient_account_encrypted",
                            "bank-account-000001234",
                            "supabase-secret",
                            "SQL DETAIL");
        }
    }

    private static List<Call> staffCalls() {
        return List.of(
                new Call("GET", BASE, null),
                new Call("GET", BASE + "/capabilities", null),
                new Call("GET", DETAIL, null),
                new Call("POST", DETAIL + "/readiness-check", "{\"expectedPayoutVersion\":0}"),
                new Call("POST", DETAIL + "/claims", "{\"expectedPayoutVersion\":0}"),
                new Call("POST", ATTEMPT_PATH + "/qr-issuances", "{\"expectedAttemptVersion\":0}"),
                new Call("POST", ATTEMPT_PATH + "/release", "{\"expectedAttemptVersion\":0}"),
                new Call(
                        "POST",
                        ATTEMPT_PATH + "/problems",
                        "{\"expectedAttemptVersion\":0,\"reason\":\"Transfer uncertain\"}"),
                new Call("POST", ATTEMPT_PATH + "/confirmation", confirmation()));
    }

    private static List<Call> adminCalls() {
        return List.of(
                new Call(
                        "POST",
                        DETAIL + "/retries",
                        "{\"expectedPayoutVersion\":0,\"reason\":\"Reviewed\"}"),
                new Call(
                        "POST",
                        ATTEMPT_PATH + "/receipt-disputes/" + CASE + "/reviews",
                        "{\"expectedAttemptVersion\":0,\"expectedCaseVersion\":0,\"action\":\"REQUEST_INFORMATION\",\"reason\":\"Review\"}"),
                new Call(
                        "POST",
                        ATTEMPT_PATH + "/resolution",
                        "{\"expectedPayoutVersion\":0,\"expectedAttemptVersion\":0,\"expectedCaseVersion\":0,\"caseId\":\""
                                + CASE
                                + "\",\"outcome\":\"CONFIRMED_NOT_SENT\",\"reason\":\"Reviewed"
                                + " non-execution\",\"evidenceIds\":[\""
                                + EVIDENCE
                                + "\"]}"));
    }

    private static String confirmation() {
        return "{\"expectedAttemptVersion\":0,\"sourceAccountId\":\"treasury-test\",\"amount\":\"95000.00\",\"bankTransferReference\":\"TEST-REFERENCE\",\"transferredAt\":\"2026-11-18T01:00:00Z\",\"evidenceIds\":[\""
                + EVIDENCE
                + "\"]}";
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1, 2, 3});
    }

    private record Call(String method, String path, String body) {
        MockHttpServletRequestBuilder request() {
            var request =
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                            HttpMethod.valueOf(method), path);
            if (body != null)
                request.contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", UUID.randomUUID().toString());
            return request;
        }
    }

    private static String bearer(List<String> roles, boolean expired) {
        try {
            Instant now = Instant.now();
            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer("user-service")
                            .audience("nexus-services")
                            .subject(ACTOR.toString())
                            .claim("roles", roles)
                            .claim("privileges", List.of("ORDER.REFUND", "MANUAL_PAYOUT.EXECUTE"))
                            .issueTime(Date.from(now.minusSeconds(300)))
                            .expirationTime(Date.from(now.plusSeconds(expired ? -120 : 600)))
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                DataJpaRepositoriesAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                EventsAutoConfiguration.class,
                IdempotentConsumerAutoConfiguration.class,
                OutboxAutoConfiguration.class
            })
    @Import({
        ManualPayoutController.class,
        SellerPayoutReceiptController.class,
        ManualPayoutExceptionHandler.class
    })
    static class TestApplication {
        @Bean
        ManualPayoutService commands() {
            return mock(ManualPayoutService.class);
        }

        @Bean
        ManualPayoutQueryService queries() {
            return mock(ManualPayoutQueryService.class);
        }

        @Bean
        SellerPayoutReceiptService receipts() {
            return mock(SellerPayoutReceiptService.class);
        }

        @Bean
        PayoutDisputeService disputes() {
            return mock(PayoutDisputeService.class);
        }
    }
}
