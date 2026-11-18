package com.group5.paymentservice.sellerbank.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.dto.response.BankAccountView;
import com.group5.paymentservice.sellerbank.event.BankAccountVerified;
import com.group5.paymentservice.sellerbank.repository.BankAccountRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@RecordApplicationEvents
@TestPropertySource(properties = "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class BankAccountReviewVersionTest extends PaymentDatabaseTestSupport {
    @Autowired BankAccountService accounts;
    @Autowired BankAccountRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext context;
    @Autowired ApplicationEvents events;
    @MockitoBean JwtDecoder decoder;
    private MockMvc mvc;
    private CurrentUser seller;
    private CurrentUser admin;

    @BeforeEach
    void setup() {
        seller = new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of());
        admin = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of());
        Jwt token = Jwt.withTokenValue("admin").header("alg", "RS256").subject(admin.id().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of("ADMIN")).claim("privileges", List.of()).build();
        when(decoder.decode("admin")).thenReturn(token);
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void staleApprovalOrRejectionCannotVerifyChangedAccountOrReleaseItsHeldPayout() throws Exception {
        var reviewed = accounts.create(seller, bank("000000001111"));
        UUID payoutId = heldPayout();
        var changed = accounts.update(seller, reviewed.id(), bank("000000002222"));
        assertThat(changed.version()).isGreaterThan(reviewed.version());

        // Admin đang duyệt bản A; thay đổi của seller sang B phải buộc admin mở lại dữ liệu mới.
        for (String decision : List.of("VERIFIED", "REJECTED")) {
            verify(reviewed.id(), "{\"verificationStatus\":\"" + decision
                    + "\",\"reason\":\"Review result\",\"expectedVersion\":" + reviewed.version() + "}")
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("PAYMENT_SELLER_BANK_ACCOUNT_CONFLICT"));
        }
        assertThat(jdbc.queryForMap("SELECT verification_status::text,verified_by,verified_at,version FROM seller_bank WHERE id=?",
                reviewed.id())).containsEntry("verification_status", "UNVERIFIED")
                .containsEntry("verified_by", null).containsEntry("verified_at", null)
                .containsEntry("version", changed.version());
        assertThat(payoutStatus(payoutId)).isEqualTo("ON_HOLD");
        assertThat(verificationEvents()).isZero();

        verify(changed.id(), "{\"verificationStatus\":\"VERIFIED\",\"expectedVersion\":" + changed.version() + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.accountLast4").value("2222"))
                .andExpect(jsonPath("$.data.version").value(changed.version() + 1));
        assertThat(payoutStatus(payoutId)).isEqualTo("PENDING");
        assertThat(verificationEvents()).isEqualTo(1);
    }

    @Test
    void verificationRequiresAnExplicitNonNegativeReviewedVersion() throws Exception {
        var account = accounts.create(seller, bank("000000001111"));
        for (String version : List.of("", ",\"expectedVersion\":null", ",\"expectedVersion\":-1")) {
            verify(account.id(), "{\"verificationStatus\":\"VERIFIED\"" + version + "}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PAYMENT_SELLER_BANK_ACCOUNT_INVALID"));
        }
        assertThat(accounts.findVerifiedDefault(seller.id())).isEmpty();
        assertThat(verificationEvents()).isZero();
    }

    @Test
    void repositoryCompareAndSetCannotWriteAReviewAgainstAnOlderVersion() {
        var reviewed = accounts.create(seller, bank("000000001111"));
        var changed = accounts.update(seller, reviewed.id(), bank("000000002222"));
        assertThat(repository.verify(reviewed.id(), admin.id(), "VERIFIED", null, reviewed.version())).isZero();
        var current = repository.find(reviewed.id()).orElseThrow();
        assertThat(current.version()).isEqualTo(changed.version());
        assertThat(current.accountLast4()).isEqualTo("2222");
        assertThat(current.verificationStatus()).isEqualTo("UNVERIFIED");
        assertThat(current.verifiedBy()).isNull();
    }

    @Test
    void concurrentDecisionsOnTheSameReviewedVersionOnlyAllowOneWrite() throws Exception {
        var reviewed = accounts.create(seller, bank("000000001111"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var approve = pool.submit(() -> decideAfter(start, reviewed, "VERIFIED"));
            var reject = pool.submit(() -> decideAfter(start, reviewed, "REJECTED"));
            start.countDown();
            assertThat(List.of(approve.get(20, TimeUnit.SECONDS), reject.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(repository.find(reviewed.id()).orElseThrow().version()).isEqualTo(reviewed.version() + 1);
    }

    private int decideAfter(CountDownLatch start, BankAccountView reviewed, String decision) throws InterruptedException {
        start.await();
        try {
            accounts.verify(admin, reviewed.id(), new BankVerificationRequest(decision, "Review result", reviewed.version()));
            return 200;
        } catch (DomainException conflict) {
            return conflict.getCode();
        }
    }

    private org.springframework.test.web.servlet.ResultActions verify(UUID id, String body) throws Exception {
        return mvc.perform(post("/api/v1/payments/seller-bank-accounts/" + id + "/verify")
                .header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long verificationEvents() {
        return events.stream(BankAccountVerified.class).filter(event -> event.sellerId().equals(seller.id())).count();
    }

    private String payoutStatus(UUID id) {
        return jdbc.queryForObject("SELECT status::text FROM payouts WHERE id=?", String.class, id);
    }

    private static BankAccountRequest bank(String number) {
        return new BankAccountRequest("970415", number, "TEST REVIEW", true, true);
    }

    private UUID heldPayout() {
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        UUID session = jdbc.queryForObject("""
                INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,due_at,status,
                    source_event_id,idempotency_key,paid_at)
                VALUES (?,?,100000,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour') RETURNING id
                """, UUID.class, group, buyer, group.toString().replace("-", "").toUpperCase(Locale.ROOT),
                UUID.randomUUID(), UUID.randomUUID().toString());
        UUID transaction = jdbc.queryForObject("""
                INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,type,idempotency_key,received_at)
                VALUES (?,'SEPAY',?,100000,'SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
                """, UUID.class, session, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        UUID instruction = jdbc.queryForObject("""
                INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,seller_id,
                    amount,due_at,status,paid_at)
                VALUES (?,?,?,?,?,100000,now()+interval '1 hour','PAID',now()-interval '1 hour') RETURNING id
                """, UUID.class, order, session, transaction, buyer, seller.id());
        return jdbc.queryForObject("""
                INSERT INTO payouts(order_id,session_id,instruction_id,seller_id,order_amount,shipping_fee,
                    platform_fee_rate,platform_fee,net_amount,currency,status,provider,source_event_id)
                VALUES (?,?,?,?,100000,5000,0,0,95000,'VND','ON_HOLD','sandbox',?) RETURNING id
                """, UUID.class, order, session, instruction, seller.id(), UUID.randomUUID());
    }
}
