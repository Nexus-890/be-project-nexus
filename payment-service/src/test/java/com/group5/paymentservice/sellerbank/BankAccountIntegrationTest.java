package com.group5.paymentservice.sellerbank;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@TestPropertySource(properties = "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class BankAccountIntegrationTest extends PaymentDatabaseTestSupport {
    @Autowired private BankAccountService accounts;
    @Autowired private BankAccountCipher cipher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @MockitoBean private JwtDecoder decoder;
    private MockMvc mvc;
    private CurrentUser seller;
    private CurrentUser admin;

    @BeforeEach
    void setup() {
        seller = user("SELLER");
        admin = user("ADMIN");
        reset(decoder);
        when(decoder.decode("seller")).thenReturn(token(seller));
        when(decoder.decode("admin")).thenReturn(token(admin));
        when(decoder.decode("buyer")).thenReturn(token(user("BUYER")));
        mvc = webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void bankNumbersStayEncryptedAndResponsesOnlyExposeLastFourDigits() throws Exception {
        var result = mvc.perform(post("/api/v1/payments/seller-bank-accounts").header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON).content(body("000012345678", true)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.accountLast4").value("5678"))
                .andExpect(jsonPath("$.data.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.data.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.data.accountNumberEncrypted").doesNotExist()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("000012345678");
        String encrypted = jdbc.queryForObject("SELECT account_number_encrypted FROM seller_bank WHERE seller_id=?", String.class, seller.id());
        assertThat(encrypted).doesNotContain("000012345678");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("000012345678");
        mvc.perform(get("/api/v1/payments/seller-bank-accounts/me").header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void changingBankDetailsClearsVerificationWhileUnchangedUpdatesKeepIt() {
        var account = accounts.create(seller, request("000012345678", true));
        accounts.verify(admin, account.id(), new BankVerificationRequest("VERIFIED", null, account.version()));
        assertThat(accounts.findVerifiedDefault(seller.id())).isPresent();
        var unchanged = accounts.update(seller, account.id(), request("000012345678", true));
        assertThat(unchanged.verificationStatus()).isEqualTo("VERIFIED");
        var changed = accounts.update(seller, account.id(), request("000087654321", true));
        assertThat(changed.verificationStatus()).isEqualTo("UNVERIFIED");
        assertThat(changed.verifiedAt()).isNull();
        assertThat(changed.verifiedBy()).isNull();
        assertThat(changed.rejectionReason()).isNull();
        assertThat(accounts.findVerifiedDefault(seller.id())).isEmpty();
        assertThat(cipher.decrypt(jdbc.queryForObject("SELECT account_number_encrypted FROM seller_bank WHERE id=?", String.class, account.id())))
                .isEqualTo("000087654321");
    }

    @Test
    void rejectRequiresReasonAndChangingEachIdentityFieldRequiresVerificationAgain() {
        var account = accounts.create(seller, request("000012345678", true));
        assertThatThrownBy(() -> accounts.verify(admin, account.id(), new BankVerificationRequest("REJECTED", " ", account.version())))
                .isInstanceOf(DomainException.class);
        var rejected = accounts.verify(admin, account.id(), new BankVerificationRequest("REJECTED", "Name mismatch", account.version()));
        assertThat(rejected.rejectionReason()).isEqualTo("Name mismatch");
        assertThat(rejected.verifiedBy()).isEqualTo(admin.id());
        var changed = accounts.update(seller, account.id(), new BankAccountRequest("970416", "000012345678", "TEST SELLER", true, true));
        assertThat(changed.verificationStatus()).isEqualTo("UNVERIFIED");
        accounts.verify(admin, account.id(), new BankVerificationRequest("VERIFIED", null, changed.version()));
        changed = accounts.update(seller, account.id(), new BankAccountRequest("970416", "000012345678", "NEW HOLDER", true, true));
        assertThat(changed.verificationStatus()).isEqualTo("UNVERIFIED");
    }

    @Test
    void sellersCannotReadOrChangeAnotherSellersAccountsAndOnlyAdminCanVerify() throws Exception {
        var other = user("SELLER");
        var account = accounts.create(other, request("000012345678", true));
        mvc.perform(get("/api/v1/payments/seller-bank-accounts/me").header("Authorization", "Bearer seller"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(put("/api/v1/payments/seller-bank-accounts/" + account.id()).header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON).content(body("000012345678", true)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT_SELLER_BANK_ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/api/v1/payments/seller-bank-accounts/" + account.id() + "/default").header("Authorization", "Bearer seller"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/payments/seller-bank-accounts/" + account.id() + "/verify").header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verificationStatus\":\"VERIFIED\",\"expectedVersion\":" + account.version() + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/seller-bank-accounts/me").header("Authorization", "Bearer buyer"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/seller-bank-accounts/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/seller-bank-accounts/" + account.id() + "/verify").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verificationStatus\":\"VERIFIED\",\"expectedVersion\":" + account.version() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"));
    }

    @Test
    void invalidAccountDataUsesTheNamedProblemCodeWithoutEchoingTheAccount() throws Exception {
        String response = mvc.perform(post("/api/v1/payments/seller-bank-accounts").header("Authorization", "Bearer seller")
                        .contentType(MediaType.APPLICATION_JSON).content(body("not-a-bank-account", true)))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_SELLER_BANK_ACCOUNT_INVALID")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("not-a-bank-account");
    }

    @Test
    void concurrentDefaultChangesSerializeAndInactiveAccountsCannotBecomeDefault() throws Exception {
        var first = accounts.create(seller, request("000012345678", false));
        var second = accounts.create(seller, request("000087654321", false));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> accounts.setDefault(seller, first.id()));
            var b = pool.submit(() -> accounts.setDefault(seller, second.id()));
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_bank WHERE seller_id=? AND is_default AND active", Integer.class, seller.id()))
                .isEqualTo(1);
        accounts.update(seller, first.id(), new BankAccountRequest("970415", "000012345678", "TEST SELLER", false, false));
        assertThatThrownBy(() -> accounts.setDefault(seller, first.id())).isInstanceOf(DomainException.class);
    }

    private static BankAccountRequest request(String number, boolean isDefault) {
        return new BankAccountRequest("970415", number, "TEST SELLER", isDefault, true);
    }
    private static String body(String number, boolean isDefault) {
        return "{\"bankCode\":\"970415\",\"accountNumber\":\"" + number
                + "\",\"accountHolderName\":\"TEST SELLER\",\"isDefault\":" + isDefault + "}";
    }
    private static CurrentUser user(String role) { return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of()); }
    private static Jwt token(CurrentUser user) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(user.id().toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).claim("roles", List.copyOf(user.roles()))
                .claim("privileges", List.of()).build();
    }
}
