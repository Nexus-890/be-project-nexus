package com.group5.paymentservice.payout;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.sellerbank.BankAccountRequest;
import com.group5.paymentservice.sellerbank.BankAccountService;
import com.group5.paymentservice.sellerbank.BankVerificationRequest;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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

    private static String bearer(UUID id, String role) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer("user-service").audience("nexus-services").subject(id.toString())
                    .claim("roles", List.of(role)).claim("privileges", List.of()).issueTime(Date.from(now))
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
