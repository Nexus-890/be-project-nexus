package com.group5.paymentservice.payout.controller;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payout.enums.PayoutStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Dùng JWT RS256 thật với security/controller/service của ứng dụng và database PostgreSQL. */
@TestPropertySource(properties = {
        "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "nexus.payment.payout-eligibility.cron=-", "nexus.payout.callback-secret=payout-http-test-only-secret",
        "nexus.security.public-endpoints[1].method=POST",
        "nexus.security.public-endpoints[1].path=/api/v1/payments/payouts/callbacks/{provider}"
})
class PayoutHttpTest extends PaymentDatabaseTestSupport {
    private static final String BASE = "/api/v1/payments/payouts";
    private static final String BANKS = "/api/v1/payments/seller-bank-accounts";
    private static final String CALLBACK_SECRET = "payout-http-test-only-secret";
    private static final RSAKey KEY = signingKey();
    private static final HttpServer JWKS = jwksServer();
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankAccountService banks;
    @Autowired JsonMapper mapper;
    private MockMvc mvc;
    private UUID sellerId;
    private UUID otherSellerId;
    private UUID adminId;

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("nexus.security.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll static void stopKeys() { JWKS.stop(0); }

    @BeforeEach void setup() {
        sellerId = UUID.randomUUID(); otherSellerId = UUID.randomUUID(); adminId = UUID.randomUUID();
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test void adminCollectionsRejectAnonymousSellerBuyerAndSupportIncludingPrivilegedSupport() throws Exception {
        for (String route : List.of(BASE, BANKS)) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            for (String role : List.of("BUYER", "SELLER", "SUPPORT_STAFF")) {
                mvc.perform(get(route).header(HttpHeaders.AUTHORIZATION, bearer(sellerId, role)))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            }
            mvc.perform(get(route).header(HttpHeaders.AUTHORIZATION,
                            bearer(sellerId, "SUPPORT_STAFF", "ORDER.REFUND")))
                    .andExpect(status().isForbidden());
            mvc.perform(get(route).param("sellerId", UUID.randomUUID().toString())
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminId, "ADMIN")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                    .andExpect(jsonPath("$.page.totalElements").value(0));
        }
    }

    @Test void adminNeedsActionFiltersEveryStatusSellerAndInclusiveCreationRangeBeforePagingWithoutWrites() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        for (PayoutStatus ignored : PayoutStatus.values()) fixtures.add(fixture(sellerId));
        Fixture outsideRange = fixture(sellerId);
        Fixture other = fixture(otherSellerId);
        var start = java.time.OffsetDateTime.parse("2026-01-15T08:00:00Z");
        for (int i = 0; i < fixtures.size(); i++) {
            jdbc.update("UPDATE payouts SET status=?::payout_status,created_at=? WHERE id=?",
                    PayoutStatus.values()[i].name(), start.plusMinutes(i), fixtures.get(i).payout());
        }
        jdbc.update("UPDATE payouts SET status='FAILED',created_at=? WHERE id=?", start.minusMinutes(1), outsideRange.payout());
        jdbc.update("UPDATE payouts SET status='FAILED',created_at=? WHERE id=?", start, other.payout());
        var counts = readCounts();
        String auth = bearer(adminId, "ADMIN");
        for (int page = 0; page < 2; page++) {
            var response = mvc.perform(get(BASE).param("sellerId", sellerId.toString())
                            .param("status", "ON_HOLD", "UNKNOWN", "FAILED", "RETURNED")
                            .param("from", start.withOffsetSameInstant(java.time.ZoneOffset.ofHours(7)).toString())
                            .param("to", start.plusMinutes(6).toString()).param("page", Integer.toString(page)).param("size", "2")
                            .header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].sellerId").value(sellerId.toString()))
                    .andExpect(jsonPath("$.data[0].netAmount").value(95000))
                    .andExpect(jsonPath("$.data[0].updatedAt").exists())
                    .andExpect(jsonPath("$.data[0].history").doesNotExist())
                    .andExpect(jsonPath("$.page.number").value(page)).andExpect(jsonPath("$.page.size").value(2))
                    .andExpect(jsonPath("$.page.totalElements").value(4)).andExpect(jsonPath("$.page.totalPages").value(2))
                    .andReturn().getResponse().getContentAsString();
            var data = mapper.readTree(response).get("data");
            int[] expected = page == 0 ? new int[]{6, 5} : new int[]{3, 0};
            for (int i = 0; i < expected.length; i++) {
                assertThat(data.get(i).get("id").asString()).isEqualTo(fixtures.get(expected[i]).payout().toString());
            }
            assertThat(response).doesNotContain("000000001234", "account_number_encrypted", "recipient_account_encrypted");
        }
        mvc.perform(get(BASE).param("sellerId", sellerId.toString()).param("status", "ON_HOLD")
                        .param("from", start.toString()).param("to", start.toString())
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(fixtures.getFirst().payout().toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        assertThat(readCounts()).isEqualTo(counts);
    }

    @Test void adminPayoutPaginationUsesUuidToBreakCreationTimeTiesAndHandlesEmptyPages() throws Exception {
        Fixture first = fixture(sellerId), second = fixture(sellerId);
        var sameTime = java.time.OffsetDateTime.parse("2026-02-01T00:00:00Z");
        jdbc.update("UPDATE payouts SET created_at=? WHERE id IN (?,?)", sameTime, first.payout(), second.payout());
        var expected = jdbc.queryForList("SELECT id FROM payouts WHERE seller_id=? ORDER BY created_at DESC,id DESC", UUID.class, sellerId);
        String auth = bearer(adminId, "ADMIN");
        for (int page = 0; page < 2; page++) {
            mvc.perform(get(BASE).param("sellerId", sellerId.toString()).param("size", "1").param("page", "" + page)
                            .header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(expected.get(page).toString()))
                    .andExpect(jsonPath("$.page.totalElements").value(2));
        }
        mvc.perform(get(BASE).param("sellerId", sellerId.toString()).param("size", "1").param("page", "2")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test void adminBankReviewOrdersOldestUnverifiedFirstAndFiltersBeforePagingWithoutLeakingNumbers() throws Exception {
        var seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of());
        var other = new CurrentUser(otherSellerId, Set.of("SELLER"), Set.of());
        var verified = banks.create(seller, new BankAccountRequest("970436", "000000001111", "REVIEW VERIFIED", false, true));
        banks.verify(new CurrentUser(adminId, Set.of("ADMIN"), Set.of()), verified.id(),
                new BankVerificationRequest("VERIFIED", null, verified.version()));
        var oldest = banks.create(seller, new BankAccountRequest("970436", "000000002222", "REVIEW OLDEST", false, true));
        var next = banks.create(other, new BankAccountRequest("970418", "000000003333", "REVIEW OTHER", false, true));
        var last = banks.create(seller, new BankAccountRequest("970436", "000000004444", "REVIEW LAST", false, true));
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE seller_bank SET created_at=? WHERE id=?", java.time.OffsetDateTime.parse("1900-01-01T00:00:00Z").plusDays(i),
                    List.of(verified.id(), oldest.id(), next.id(), last.id()).get(i));
        }
        var counts = readCounts();
        String auth = bearer(adminId, "ADMIN");
        var response = mvc.perform(get(BANKS).param("size", "2").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(oldest.id().toString()))
                .andExpect(jsonPath("$.data[1].id").value(next.id().toString()))
                .andExpect(jsonPath("$.data[0].accountLast4").value("2222"))
                .andExpect(jsonPath("$.data[0].version").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("000000002222", "000000003333", "accountNumber", "account_number_encrypted", "v1:");
        for (int page = 0; page < 2; page++) {
            mvc.perform(get(BANKS).param("sellerId", sellerId.toString()).param("verificationStatus", "UNVERIFIED")
                            .param("size", "1").param("page", "" + page).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value((page == 0 ? oldest : last).id().toString()))
                    .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2));
        }
        mvc.perform(get(BANKS).param("sellerId", sellerId.toString()).param("verificationStatus", "VERIFIED")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(verified.id().toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        assertThat(readCounts()).isEqualTo(counts);
    }

    @Test void adminBankReviewKeepsInactiveUnverifiedAccountsBehindActiveOnesAcrossPages() throws Exception {
        var seller = new CurrentUser(sellerId, Set.of("SELLER"), Set.of());
        var inactive = banks.create(seller, new BankAccountRequest("970436", "000000005555", "REVIEW INACTIVE", false, true));
        banks.update(seller, inactive.id(), new BankAccountRequest("970436", "000000005555", "REVIEW INACTIVE", false, false));
        var active = banks.create(seller, new BankAccountRequest("970436", "000000006666", "REVIEW ACTIVE", false, true));
        var start = java.time.OffsetDateTime.parse("2026-02-01T00:00:00Z");
        jdbc.update("UPDATE seller_bank SET created_at=? WHERE id=?", start, inactive.id());
        jdbc.update("UPDATE seller_bank SET created_at=? WHERE id=?", start.plusDays(1), active.id());

        String auth = bearer(adminId, "ADMIN");
        for (int page = 0; page < 2; page++) {
            mvc.perform(get(BANKS).param("sellerId", sellerId.toString()).param("size", "1").param("page", "" + page)
                            .header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value((page == 0 ? active : inactive).id().toString()))
                    .andExpect(jsonPath("$.data[0].verificationStatus").value("UNVERIFIED"))
                    .andExpect(jsonPath("$.data[0].active").value(page == 0))
                    .andExpect(jsonPath("$.page.number").value(page)).andExpect(jsonPath("$.page.size").value(1))
                    .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2));
        }
    }

    @Test void adminListFiltersRejectMalformedValuesAndInvalidPaginationAsProblemDetails() throws Exception {
        String auth = bearer(adminId, "ADMIN");
        for (String route : List.of(BASE, BANKS)) {
            for (String[] invalid : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                    new String[]{"size", "101"}, new String[]{"page", "NaN"}, new String[]{"sellerId", "not-a-uuid"})) {
                mvc.perform(get(route).param(invalid[0], invalid[1]).header(HttpHeaders.AUTHORIZATION, auth))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            }
        }
        mvc.perform(get(BASE).param("status", "FAILED", "NOT_A_STATUS").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BANKS).param("verificationStatus", "NOT_A_STATUS").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("from", "2026-01-01").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_PAYOUT_INVALID_REQUEST"));
    }

    private List<Long> readCounts() {
        return List.of("payouts", "seller_bank", "payout_history", "payment_transactions", "outbox").stream()
                .map(table -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).toList();
    }

    @Test void payoutReadsAndRetryRequireAuthenticationAndTheCorrectRole() throws Exception {
        String detail = BASE + "/" + UUID.randomUUID();
        mvc.perform(get(BASE + "/me")).andExpect(status().isUnauthorized());
        mvc.perform(get(detail)).andExpect(status().isUnauthorized());
        mvc.perform(post(detail + "/retry")).andExpect(status().isUnauthorized());
        String buyer = bearer(UUID.randomUUID(), "BUYER");
        mvc.perform(get(BASE + "/me").header(HttpHeaders.AUTHORIZATION, buyer))
                .andExpect(status().isForbidden());
        mvc.perform(get(detail).header(HttpHeaders.AUTHORIZATION, buyer)).andExpect(status().isForbidden());
        mvc.perform(post(detail + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test void sellerListIsPaginatedAndCannotBeRedirectedToAnotherSeller() throws Exception {
        Fixture first = fixture(sellerId);
        fixture(sellerId);
        fixture(otherSellerId);
        String firstPage = mvc.perform(get(BASE + "/me").param("page", "0").param("size", "1")
                        .param("sellerId", otherSellerId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data[0].simulated").value(true))
                .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2)).andExpect(jsonPath("$.page.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        String firstId = mapper.readTree(firstPage).get("data").get(0).get("id").asString();
        mvc.perform(get(BASE + "/me").param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data[0].id").value(org.hamcrest.Matchers.not(firstId)));
        mvc.perform(get(BASE + "/me").param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER")))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT seller_id FROM payouts WHERE id=?", UUID.class, first.payout()))
                .isEqualTo(sellerId);
    }

    @Test void payoutDetailHidesAnotherSellerButAllowsItsOwnerAndAdminWithMaskedHistory() throws Exception {
        Fixture fixture = fixture(sellerId);
        mvc.perform(get(BASE + "/" + fixture.payout()).header(HttpHeaders.AUTHORIZATION, bearer(otherSellerId, "SELLER")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_PAYOUT_NOT_FOUND"));
        for (String auth : List.of(bearer(sellerId, "SELLER"), bearer(adminId, "ADMIN"))) {
            String response = mvc.perform(get(BASE + "/" + fixture.payout()).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value(fixture.order().toString()))
                    .andExpect(jsonPath("$.data.recipientAccountLast4").value("1234"))
                    .andExpect(jsonPath("$.data.history[0].event").value("SENT"))
                    .andExpect(jsonPath("$.data.history[0].recipientAccountLast4").value("1234"))
                    .andExpect(jsonPath("$.data.recipientAccountEncrypted").doesNotExist())
                    .andExpect(jsonPath("$.data.history[0].recipientAccountEncrypted").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("000000001234", "recipient_account_encrypted", "accountNumber");
        }
    }

    @Test void adminRetryRequiresAKeyAndAnHttpReplayQueuesOnlyOneRetry() throws Exception {
        Fixture fixture = fixture(sellerId);
        jdbc.update("UPDATE payouts SET status='FAILED' WHERE id=?", fixture.payout());
        String route = BASE + "/" + fixture.payout() + "/retry";
        String auth = bearer(adminId, "ADMIN");
        mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_PAYOUT_IDEMPOTENCY_INVALID"));
        String key = UUID.randomUUID().toString();
        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(post(route).header(HttpHeaders.AUTHORIZATION, auth).header("Idempotency-Key", key))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_history WHERE payout_id=? AND event='RETRIED'",
                Integer.class, fixture.payout())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM payout_history WHERE payout_id=? AND event='RETRIED'",
                UUID.class, fixture.payout())).isEqualTo(adminId);
    }

    @Test void callbacksReachSignatureValidationWithoutAJwtAndRejectForgedOrExpiredBodies() throws Exception {
        Fixture fixture = fixture(sellerId);
        byte[] body = callback(fixture);
        mvc.perform(post(BASE + "/callbacks/sandbox").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_PAYOUT_CALLBACK_SIGNATURE_INVALID"));
        String now = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post(BASE + "/callbacks/sandbox").header("X-Payout-Timestamp", now)
                        .header("X-Payout-Signature", "0".repeat(64)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PAYMENT_PAYOUT_CALLBACK_SIGNATURE_INVALID"));
        String expired = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        mvc.perform(post(BASE + "/callbacks/sandbox").header("X-Payout-Timestamp", expired)
                        .header("X-Payout-Signature", signature(expired, body)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status::text FROM payouts WHERE id=?", String.class, fixture.payout()))
                .isEqualTo("PROCESSING");
        assertThat(ledgerCount(fixture)).isZero();
    }

    @Test void signedDuplicateCallbackReturnsTheProviderProtocolAndRecordsOneMovement() throws Exception {
        Fixture fixture = fixture(sellerId);
        byte[] body = callback(fixture);
        String now = Long.toString(Instant.now().getEpochSecond());
        String signature = signature(now, body);
        for (int duplicate = 0; duplicate < 2; duplicate++) {
            mvc.perform(post(BASE + "/callbacks/sandbox").header("X-Payout-Timestamp", now)
                            .header("X-Payout-Signature", signature).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM payouts WHERE id=?", String.class, fixture.payout()))
                .isEqualTo("SUCCEEDED");
        assertThat(ledgerCount(fixture)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payout_history WHERE payout_id=? AND event='SUCCEEDED'",
                Integer.class, fixture.payout())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id=? AND event_type='PayoutSucceeded'",
                Integer.class, fixture.payout().toString())).isEqualTo(1);
    }

    @Test void bankHttpOperationsMaskAccountsAndEnforceOwnerAndVerifierRoles() throws Exception {
        String body = """
                {"bankCode":"970415","accountNumber":"000000001234","accountHolderName":"HTTP TEST SELLER",
                 "isDefault":true,"active":true,"sellerId":"%s"}
                """.formatted(otherSellerId);
        mvc.perform(post(BANKS).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(BANKS).header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String response = mvc.perform(post(BANKS).header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data.accountLast4").value("1234"))
                .andExpect(jsonPath("$.data.accountNumber").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(response).get("data").get("id").asString();
        assertThat(response).doesNotContain("000000001234");
        mvc.perform(put(BANKS + "/" + id).header(HttpHeaders.AUTHORIZATION, bearer(otherSellerId, "SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_SELLER_BANK_ACCOUNT_NOT_FOUND"));
        mvc.perform(post(BANKS + "/" + id + "/default").header(HttpHeaders.AUTHORIZATION, bearer(otherSellerId, "SELLER")))
                .andExpect(status().isNotFound());
        mvc.perform(post(BANKS + "/" + id + "/verify").header(HttpHeaders.AUTHORIZATION, bearer(sellerId, "SELLER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verificationStatus\":\"VERIFIED\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BANKS + "/" + id + "/verify").header(HttpHeaders.AUTHORIZATION, bearer(adminId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verificationStatus\":\"VERIFIED\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"));
        mvc.perform(get(BANKS + "/me").header(HttpHeaders.AUTHORIZATION, bearer(otherSellerId, "SELLER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
    }

    private int ledgerCount(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE session_id=? AND type='PAYOUT'",
                Integer.class, fixture.session());
    }

    private Fixture fixture(UUID seller) {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        UUID session = jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,
                    status,source_event_id,idempotency_key,paid_at)
                VALUES (?,?,100000,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour') RETURNING id
                """, UUID.class, group, buyer, group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                UUID.randomUUID(), UUID.randomUUID().toString());
        UUID receipt = jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)
                VALUES (?,'SEPAY',?,100000,'SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
                """, UUID.class, session, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        UUID instruction = jdbc.queryForObject("""
                INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,
                    amount,due_at,status,paid_at) VALUES (?,?,?,?,?,100000,now()+interval '1 hour','PAID',now()-interval '1 hour') RETURNING id
                """, UUID.class, order, session, receipt, buyer, seller);
        var account = banks.create(new CurrentUser(seller, Set.of("SELLER"), Set.of()),
                new BankAccountRequest("970415", "000000001234", "HTTP TEST SELLER", true, true));
        banks.verify(new CurrentUser(adminId, Set.of("ADMIN"), Set.of()), account.id(),
                new BankVerificationRequest("VERIFIED", null, account.version()));
        UUID payout = jdbc.queryForObject("""
                INSERT INTO payouts(order_id,session_id,instruction_id,seller_id,seller_bank_id,
                    recipient_bank_code,recipient_account_encrypted,recipient_account_last4,recipient_holder_name,
                    order_amount,shipping_fee,platform_fee_rate,platform_fee,net_amount,currency,status,
                    attempt_count,attempt_started_at,provider,source_event_id)
                SELECT ?,?,?,seller_id,id,bank_code,account_number_encrypted,account_last4,account_holder_name,
                    100000,5000,0,0,95000,'VND','PROCESSING',1,now(),'sandbox',? FROM seller_bank WHERE id=? RETURNING id
                """, UUID.class, order, session, instruction, UUID.randomUUID(), account.id());
        jdbc.update("""
                INSERT INTO payout_history(payout_id,event_seq,event,from_status,to_status,attempt_no,amount,
                    seller_bank_id,recipient_account_last4,provider,simulated,actor_id)
                VALUES (?,1,'SENT','PENDING','PROCESSING',1,95000,?,'1234','sandbox',true,?)
                """, payout, account.id(), new UUID(0, 0));
        return new Fixture(payout, order, session);
    }

    private byte[] callback(Fixture fixture) {
        return ("{\"payoutId\":\"" + fixture.payout() + "\",\"attempt\":1,\"idempotencyKey\":\"payout:"
                + fixture.payout() + ":1\",\"state\":\"SUCCEEDED\",\"reference\":\"http-" + fixture.payout()
                + "\",\"providerStatus\":\"SIMULATED_SUCCESS\"}").getBytes(StandardCharsets.UTF_8);
    }

    private String signature(String timestamp, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CALLBACK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static String bearer(UUID id, String role, String... privileges) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer("user-service").audience("nexus-services").subject(id.toString())
                    .claim("roles", List.of(role)).claim("privileges", List.of(privileges)).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(600))).build();
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("payout-http-test").build(), claims);
            token.sign(new RSASSASigner(KEY));
            return "Bearer " + token.serialize();
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static RSAKey signingKey() {
        try { return new RSAKeyGenerator(2048).keyID("payout-http-test").generate(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start(); return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private record Fixture(UUID payout, UUID order, UUID session) { }
}
