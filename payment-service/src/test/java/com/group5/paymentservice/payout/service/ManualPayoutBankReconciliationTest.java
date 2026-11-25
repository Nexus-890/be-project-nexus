package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.group5.common_events.EventEnvelope;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.ManualPayoutView;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@TestPropertySource(
        properties = {
            "nexus.payout.execution-mode=MANUAL",
            "nexus.payout.manual.enabled=true",
            "nexus.payout.jobs-enabled=false",
            "nexus.payout.manual.source-accounts[0].id=platform-main",
            "nexus.payout.manual.source-accounts[0].alias=Platform test source",
            "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class ManualPayoutBankReconciliationTest extends PaymentDatabaseTestSupport {
    private static final String ACCOUNT = "0000000002";
    private static final DateTimeFormatter SEPAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ManualPayoutService manual;
    @Autowired ManualPayoutQueryService queries;
    @Autowired PayoutEligibilityService completions;
    @Autowired BankAccountService banks;
    @Autowired ManualPayoutProperties settings;
    @Autowired PayoutProperties payoutSettings;
    @MockitoBean PayoutEvidenceStorage storage;
    @MockitoBean PayoutGateway gateway;
    private final CurrentUser admin = actor("ADMIN");
    private final CurrentUser support = actor("SUPPORT_STAFF");
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        settings.setEnabled(true);
        settings.setQrDeadline(Duration.ofMinutes(10));
        settings.setClaimLease(Duration.ofMinutes(5));
        settings.setSourceAccounts(
                List.of(
                        new ManualPayoutProperties.SourceAccount(
                                "platform-main", "Platform test source")));
        settings.getSepay().setReconciliationEnabled(true);
        settings.getSepay().setBankAccount("");
        settings.getSepay().setSourceAccountId("");
        payoutSettings.setExecutionMode("MANUAL");
        payoutSettings.setJobsEnabled(false);
        when(storage.configured()).thenReturn(true);
        mvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                        .build();
    }

    @Test
    void exactOutgoingTransferConfirmsTheIssuedAttemptOnceWithoutStaffInput() throws Exception {
        Fixture f = funded();
        var issued = issue(f);
        long id = IDS.incrementAndGet();
        String body = outgoing(id, ACCOUNT, issued.netAmount(), "CK " + reference(issued).toUpperCase());

        webhook(body);
        webhook(body);

        var view = detail(f);
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.confirmationSource()).isEqualTo("BANK_VERIFIED");
        assertThat(view.currentAttempt().state()).isEqualTo("SUCCEEDED");
        assertThat(view.currentAttempt().receiptStatus()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(view.currentAttempt().bankMatch().transactionId()).isEqualTo(Long.toString(id));
        assertThat(view.currentAttempt().bankMatch().outcome()).isEqualTo("CONFIRMED");
        assertThat(view.currentAttempt().bankMatch().amount()).isEqualTo(issued.netAmount());
        assertThat(movements(f)).isEqualTo(1);
        assertThat(bankRows(id)).isEqualTo(1);
        var attempt =
                jdbc.queryForMap(
                        "SELECT source_account_id,bank_transfer_reference,confirmed_by FROM"
                                + " manual_payout_attempts WHERE id=?",
                        view.currentAttempt().id());
        assertThat(attempt.get("source_account_id")).isEqualTo("platform-main");
        assertThat(attempt.get("bank_transfer_reference")).isEqualTo("FT" + id);
        assertThat(attempt.get("confirmed_by")).isEqualTo(new UUID(0, 0));
        String payload =
                jdbc.queryForObject(
                        "SELECT payload::text FROM outbox WHERE aggregate_id=? AND"
                                + " event_type='PayoutSucceeded'",
                        String.class,
                        f.id().toString());
        assertThat(payload).contains("BANK_VERIFIED");
        assertThat(view.history().stream().map(ManualPayoutView.History::actorRole))
                .contains("SYSTEM");
        var seller = queries.sellerProjection(f.seller(), f.id());
        assertThat(seller.allowedActions()).contains("CONFIRM_RECEIPT");
    }

    @Test
    void wrongAmountMovesTheIssuedAttemptToReconciliationWithoutSettlement() throws Exception {
        Fixture f = funded();
        var issued = issue(f);
        long id = IDS.incrementAndGet();

        webhook(outgoing(id, ACCOUNT, "1000", reference(issued)));

        var view = detail(f);
        assertThat(view.status()).isEqualTo("UNKNOWN");
        assertThat(view.currentAttempt().state()).isEqualTo("UNKNOWN");
        assertThat(view.currentAttempt().bankMatch().outcome()).isEqualTo("REVIEW_REQUIRED");
        assertThat(view.cases()).anyMatch(c -> "RECONCILIATION_REQUIRED".equals(c.reason()));
        assertThat(movements(f)).isZero();
    }

    @Test
    void staffConfirmedPayoutIsBankVerifiedAndASecondTransferOpensADuplicateCase()
            throws Exception {
        Fixture f = funded();
        var issued = issue(f);
        var ready =
                manual.upload(
                                support,
                                f.id(),
                                issued.currentAttempt().id(),
                                key(),
                                issued.currentAttempt().version(),
                                proof())
                        .result();
        manual.execute(
                support,
                f.id(),
                ready.currentAttempt().id(),
                null,
                "CONFIRM_TRANSFER",
                key(),
                new ConfirmationCommand(
                        ready.currentAttempt().version(),
                        "platform-main",
                        ready.netAmount(),
                        "FT" + UUID.randomUUID().toString().replace("-", ""),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        ready.evidence().stream().map(ManualPayoutView.Evidence::id).toList()));

        webhook(outgoing(IDS.incrementAndGet(), ACCOUNT, issued.netAmount(), reference(issued)));
        var verified = detail(f);
        assertThat(verified.confirmationSource()).isEqualTo("MANUAL_STAFF_CONFIRMED");
        assertThat(verified.currentAttempt().bankMatch().outcome()).isEqualTo("VERIFIED");
        assertThat(verified.cases()).isEmpty();

        webhook(outgoing(IDS.incrementAndGet(), ACCOUNT, issued.netAmount(), reference(issued)));
        var duplicate = detail(f);
        assertThat(duplicate.status()).isEqualTo("SUCCEEDED");
        assertThat(duplicate.currentAttempt().bankMatch().transfers()).isEqualTo(2);
        assertThat(duplicate.cases())
                .singleElement()
                .satisfies(c -> assertThat(c.reason()).isEqualTo("DUPLICATE_BANK_TRANSFER"));
        assertThat(movements(f)).isEqualTo(1);
    }

    @Test
    void otherAccountsAndTransfersWithoutAReferenceChangeNoPayout() throws Exception {
        Fixture f = funded();
        var issued = issue(f);
        long other = IDS.incrementAndGet();
        long plain = IDS.incrementAndGet();

        webhook(outgoing(other, "9999999999", issued.netAmount(), reference(issued)));
        webhook(outgoing(plain, ACCOUNT, issued.netAmount(), "Tra tien dien"));

        var view = detail(f);
        assertThat(view.currentAttempt().state()).isEqualTo("QR_ISSUED");
        assertThat(view.currentAttempt().bankMatch()).isNull();
        assertThat(outcome(other)).isEqualTo("WRONG_ACCOUNT");
        assertThat(outcome(plain)).isEqualTo("UNMATCHED");
        assertThat(movements(f)).isZero();
    }

    @Test
    void disabledReconciliationLeavesOutgoingTransfersUntouched() throws Exception {
        Fixture f = funded();
        var issued = issue(f);
        long id = IDS.incrementAndGet();
        settings.getSepay().setReconciliationEnabled(false);
        try {
            webhook(outgoing(id, ACCOUNT, issued.netAmount(), reference(issued)));
        } finally {
            settings.getSepay().setReconciliationEnabled(true);
        }
        assertThat(detail(f).currentAttempt().state()).isEqualTo("QR_ISSUED");
        assertThat(bankRows(id)).isZero();
    }

    @Test
    void referencesAreFoundCaseInsensitivelyInAnyMemoField() {
        var request =
                new com.group5.paymentservice.payment.dto.request.SepayWebhookRequest(
                        1L,
                        "VCB",
                        null,
                        ACCOUNT,
                        null,
                        "MBVCB.1.NXPABCDEF0123456789ABCD.CT tu 000",
                        "out",
                        null,
                        null,
                        null,
                        null,
                        "nxpabcdef0123456789abcd");
        assertThat(ManualPayoutBankReconciliationService.references(request))
                .containsExactly("NXPabcdef0123456789abcd");
    }

    private ManualPayoutView issue(Fixture f) {
        var claimed =
                manual.execute(
                                support,
                                f.id(),
                                null,
                                null,
                                "CLAIM",
                                key(),
                                new ClaimCommand(detail(f).version()))
                        .result();
        return manual.execute(
                        support,
                        f.id(),
                        claimed.currentAttempt().id(),
                        null,
                        "ISSUE_QR",
                        key(),
                        new AttemptVersionCommand(claimed.currentAttempt().version(), null))
                .result();
    }

    private static String reference(ManualPayoutView view) {
        return view.currentAttempt().reference();
    }

    private void webhook(String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.now();
        mvc.perform(
                        post("/api/v1/webhooks/sepay")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .header("X-SePay-Signature", sign(body, now))
                                .header("X-SePay-Timestamp", now.getEpochSecond()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true}"));
    }

    private String outgoing(long id, String account, String amount, String content) {
        String at =
                LocalDateTime.now(settings.getBankBusinessZone())
                        .plusSeconds(1)
                        .format(SEPAY_TIME);
        return "{\"id\":"
                + id
                + ",\"gateway\":\"VCB\",\"transactionDate\":\""
                + at
                + "\",\"accountNumber\":\""
                + account
                + "\",\"code\":null,\"content\":\""
                + content
                + "\",\"transferType\":\"out\",\"transferAmount\":"
                + amount
                + ",\"referenceCode\":\"FT"
                + id
                + "\",\"description\":\""
                + content
                + "\"}";
    }

    private static String sign(byte[] body, Instant signedAt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(
                new SecretKeySpec(
                        "unit-test-only-webhook-secret".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"));
        mac.update(Long.toString(signedAt.getEpochSecond()).getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private ManualPayoutView detail(Fixture f) {
        return queries.detail(admin, f.id());
    }

    private long movements(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND type='PAYOUT'",
                Long.class,
                f.sessionId());
    }

    private long bankRows(long id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM manual_payout_bank_transactions WHERE"
                        + " provider_transaction_id=?",
                Long.class,
                Long.toString(id));
    }

    private String outcome(long id) {
        return jdbc.queryForObject(
                "SELECT outcome FROM manual_payout_bank_transactions WHERE"
                        + " provider_transaction_id=?",
                String.class,
                Long.toString(id));
    }

    private static MockMultipartFile proof() {
        return new MockMultipartFile(
                "file",
                "proof.pdf",
                "application/pdf",
                "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\n%%EOF"
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static CurrentUser actor(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of());
    }

    private record Fixture(UUID id, UUID sessionId, CurrentUser seller) {}

    private Fixture funded() {
        CurrentUser seller = actor("SELLER");
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
        var bank =
                banks.create(
                        seller,
                        new BankAccountRequest("970436", "000000001234", "TEST SELLER", true, true));
        banks.verify(admin, bank.id(), new BankVerificationRequest("VERIFIED", null, bank.version()));
        Instant completed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        completions.completed(
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCompleted",
                        order.toString(),
                        completed,
                        "bank-reconciliation-test",
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
                session,
                seller);
    }
}
