package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.PaymentDatabaseTestSupport;
import com.group5.paymentservice.payment.event.payload.OrderCompleted;
import com.group5.paymentservice.payment.service.PayoutEligibilityService;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.ManualCommandResult;
import com.group5.paymentservice.payout.dto.response.ManualPayoutView;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import com.group5.paymentservice.sellerbank.dto.request.BankVerificationRequest;
import com.group5.paymentservice.sellerbank.service.BankAccountService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import javax.imageio.ImageIO;

/**
 * One funded checkout/session contains two seller orders. Actual Payment services and PostgreSQL
 * must preserve separate liabilities, recipient instructions and postings. Funding and proof are
 * synthetic; this test never makes a bank transfer or certifies external credit.
 */
@TestPropertySource(
        properties = {
            "nexus.payout.execution-mode=MANUAL",
            "nexus.payout.manual.enabled=true",
            "nexus.payout.jobs-enabled=false",
            "nexus.payout.manual.source-accounts[0].id=platform-main",
            "nexus.payout.manual.source-accounts[0].alias=Platform test source",
            "nexus.payout.account-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
class ManualTwoShopPayoutTest extends PaymentDatabaseTestSupport {
    private static final String ACCOUNT_A = "000001112233";
    private static final String ACCOUNT_B = "000009998877";
    @Autowired JdbcTemplate jdbc;
    @Autowired PayoutEligibilityService completions;
    @Autowired BankAccountService banks;
    @Autowired ManualPayoutService manual;
    @Autowired ManualPayoutQueryService queries;
    @Autowired PayoutProperties payoutSettings;
    @Autowired ManualPayoutProperties manualSettings;
    @MockitoBean PayoutEvidenceStorage storage;
    @MockitoSpyBean PayoutGateway gateway;

    private final CurrentUser admin = actor("ADMIN");
    private final CurrentUser support = actor("SUPPORT_STAFF");

    @BeforeEach
    void configureManualFlow() {
        payoutSettings.setExecutionMode("MANUAL");
        payoutSettings.setJobsEnabled(false);
        payoutSettings.setPlatformFeeRate(new BigDecimal("0.0250"));
        manualSettings.setEnabled(true);
        manualSettings.setClaimLease(Duration.ofMinutes(5));
        manualSettings.setQrDeadline(Duration.ofMinutes(10));
        manualSettings.setSourceAccounts(
                List.of(
                        new ManualPayoutProperties.SourceAccount(
                                "platform-main", "Platform test source")));
        when(storage.configured()).thenReturn(true);
    }

    @AfterEach
    void neverDispatchesAutomaticBankActivity() {
        verify(gateway, never()).send(any());
        verify(gateway, never()).lookup(anyString());
    }

    @Test
    void aSharedFundedCheckoutProducesTwoExactIndependentQrInstructionsAndTwoPostings()
            throws Exception {
        Checkout checkout = fundedCheckout(true);
        Order first = checkout.first();
        Order second = checkout.second();
        assertSharedCheckoutCreatesSeparatePayouts(checkout);
        assertThat(detail(first).netAmount()).isEqualTo("141375.00");
        assertThat(detail(second).netAmount()).isEqualTo("224250.00");

        // Replaying a completion and changing fee configuration must not rewrite either liability.
        completions.completed(first.completion());
        completions.completed(second.completion());
        payoutSettings.setPlatformFeeRate(new BigDecimal("0.9000"));
        var issuedFirst = issue(first, support);
        var issuedSecond = issue(second, admin);
        assertThat(issuedFirst.currentAttempt().reference())
                .isNotEqualTo(issuedSecond.currentAttempt().reference());
        assertThat(issuedFirst.recipient().accountLast4()).isEqualTo("2233");
        assertThat(issuedSecond.recipient().accountLast4()).isEqualTo("8877");
        assertQr(first, support, issuedFirst, "970436", ACCOUNT_A, "141375");
        assertQr(second, admin, issuedSecond, "970418", ACCOUNT_B, "224250");

        var readyFirst = evidence(first, support, issuedFirst);
        var readySecond = evidence(second, admin, issuedSecond);
        assertThat(readyFirst.evidence()).hasSize(1);
        assertThat(readySecond.evidence()).hasSize(1);
        assertThat(readyFirst.evidence().getFirst().id())
                .isNotEqualTo(readySecond.evidence().getFirst().id());
        var borrowedProof =
                new ConfirmationCommand(
                        readyFirst.currentAttempt().version(),
                        "platform-main",
                        readyFirst.netAmount(),
                        "CROSS" + key(),
                        now(),
                        List.of(readySecond.evidence().getFirst().id()));
        assertThatThrownBy(
                        () ->
                                command(
                                        support,
                                        first,
                                        readyFirst.currentAttempt().id(),
                                        "CONFIRM_TRANSFER",
                                        key(),
                                        borrowedProof))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        error ->
                                assertThat(error.getErrorCode())
                                        .isEqualTo("PAYMENT_MANUAL_PAYOUT_NOT_FOUND"));
        assertThat(payoutPostingCount(checkout.sessionId())).isZero();

        var secondBeforeFirstSettlement = detail(second);
        String secondSnapshot = payoutSnapshot(second);
        String firstConfirmationKey = key();
        var firstConfirmation = confirmation(readyFirst);
        var settledFirst =
                command(
                                support,
                                first,
                                readyFirst.currentAttempt().id(),
                                "CONFIRM_TRANSFER",
                                firstConfirmationKey,
                                firstConfirmation)
                        .result();
        assertThat(settledFirst.status()).isEqualTo("SUCCEEDED");
        assertThat(settledFirst.currentAttempt().receiptStatus())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(payoutSnapshot(second)).isEqualTo(secondSnapshot);
        assertThat(detail(second).version()).isEqualTo(secondBeforeFirstSettlement.version());
        assertThat(
                        command(
                                        support,
                                        first,
                                        readyFirst.currentAttempt().id(),
                                        "CONFIRM_TRANSFER",
                                        firstConfirmationKey,
                                        firstConfirmation)
                                .replay())
                .isTrue();
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(1);

        var settledSecond =
                command(
                                admin,
                                second,
                                readySecond.currentAttempt().id(),
                                "CONFIRM_TRANSFER",
                                key(),
                                confirmation(readySecond))
                        .result();
        assertThat(settledSecond.status()).isEqualTo("SUCCEEDED");
        assertThat(settledSecond.currentAttempt().receiptStatus())
                .isEqualTo("AWAITING_CONFIRMATION");
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(2);
        assertThat(
                        jdbc.queryForList(
                                """
SELECT t.amount FROM payment_transactions t
JOIN payouts p ON p.transaction_id=t.id WHERE p.session_id=? ORDER BY t.amount
""",
                                BigDecimal.class,
                                checkout.sessionId()))
                .containsExactly(new BigDecimal("141375.00"), new BigDecimal("224250.00"));
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT count(DISTINCT transaction_id) FROM payouts WHERE session_id=?
""",
                                Long.class,
                                checkout.sessionId()))
                .isEqualTo(2);

        acknowledge(first, settledFirst);
        assertThat(queries.sellerProjection(first.seller(), first.payoutId()).receiptStatus())
                .isEqualTo("CONFIRMED");
        assertThat(queries.sellerProjection(second.seller(), second.payoutId()).receiptStatus())
                .isEqualTo("AWAITING_CONFIRMATION");
        acknowledge(second, settledSecond);
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT count(*) FROM outbox o JOIN payouts p ON o.aggregate_id=p.id::text
WHERE p.session_id=? AND o.event_type='PayoutSucceeded'
""",
                                Long.class,
                                checkout.sessionId()))
                .isEqualTo(2);
    }

    @Test
    void oneHeldSellerDoesNotBlockTheOtherSellerFromTheSameCheckout() throws Exception {
        Checkout checkout = fundedCheckout(false);
        Order held = checkout.first();
        Order executable = checkout.second();
        assertSharedCheckoutCreatesSeparatePayouts(checkout);
        var heldBefore = detail(held);
        assertThat(heldBefore.status()).isEqualTo("ON_HOLD");
        assertThat(heldBefore.blockedReason()).isEqualTo("BANK_NOT_READY");
        assertThat(heldBefore.allowedActions()).doesNotContain("CLAIM");
        assertThatThrownBy(
                        () ->
                                command(
                                        support,
                                        held,
                                        null,
                                        "CLAIM",
                                        key(),
                                        new ClaimCommand(heldBefore.version())))
                .isInstanceOf(DomainException.class);
        assertThat(detail(held).currentAttempt()).isNull();

        String heldSnapshot = payoutSnapshot(held);
        var issued = issue(executable, support);
        assertQr(executable, support, issued, "970418", ACCOUNT_B, "224250");
        var ready = evidence(executable, support, issued);
        var settled =
                command(
                                support,
                                executable,
                                ready.currentAttempt().id(),
                                "CONFIRM_TRANSFER",
                                key(),
                                confirmation(ready))
                        .result();
        assertThat(settled.status()).isEqualTo("SUCCEEDED");
        assertThat(payoutSnapshot(held)).isEqualTo(heldSnapshot);
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(1);

        // Giving A a bank and explicitly recovering readiness must not change B or pay A.
        String settledSnapshot = payoutSnapshot(executable);
        verifiedBank(held.seller(), "970436", ACCOUNT_A, "TEST SHOP A");
        assertThat(detail(held).status()).isEqualTo("ON_HOLD");
        var recovered =
                command(
                                admin,
                                held,
                                null,
                                "READINESS_CHECK",
                                key(),
                                new PayoutVersionCommand(detail(held).version(), null))
                        .result();
        assertThat(recovered.status()).isEqualTo("PENDING");
        assertThat(recovered.currentAttempt()).isNull();
        assertThat(payoutSnapshot(executable)).isEqualTo(settledSnapshot);
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(1);
    }

    @Test
    void uncertaintyAfterIssuingOneSellerQrDoesNotInvalidateTheOtherSellersInstruction()
            throws Exception {
        Checkout checkout = fundedCheckout(true);
        Order uncertain = checkout.first();
        Order executable = checkout.second();
        var issuedFirst = issue(uncertain, support);
        var issuedSecond = issue(executable, admin);
        byte[] frozenSecondQr =
                manual.qr(admin, executable.payoutId(), issuedSecond.currentAttempt().id());
        String otherSnapshot = payoutSnapshot(executable);

        var bank = banks.list(uncertain.seller(), 0, 20).content().getFirst();
        banks.update(
                uncertain.seller(),
                bank.id(),
                new BankAccountRequest("970436", "999991112233", "TEST SHOP A", true, true));
        assertThat(detail(uncertain).status()).isEqualTo("UNKNOWN");
        assertThat(detail(uncertain).currentAttempt().id())
                .isEqualTo(issuedFirst.currentAttempt().id());
        assertThat(payoutSnapshot(executable)).isEqualTo(otherSnapshot);
        assertThat(manual.qr(admin, executable.payoutId(), issuedSecond.currentAttempt().id()))
                .isEqualTo(frozenSecondQr);
        assertQr(executable, admin, issuedSecond, "970418", ACCOUNT_B, "224250");
        var ready = evidence(executable, admin, issuedSecond);
        command(
                admin,
                executable,
                ready.currentAttempt().id(),
                "CONFIRM_TRANSFER",
                key(),
                confirmation(ready));
        assertThat(detail(executable).status()).isEqualTo("SUCCEEDED");
        assertThat(detail(uncertain).status()).isEqualTo("UNKNOWN");
        assertThat(payoutPostingCount(checkout.sessionId())).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT t.amount FROM payment_transactions t JOIN payouts p ON p.transaction_id=t.id
WHERE p.id=?
""",
                                BigDecimal.class,
                                executable.payoutId()))
                .isEqualByComparingTo("224250.00");
    }

    private Checkout fundedCheckout(boolean firstBankReady) {
        CurrentUser firstSeller = actor("SELLER"), secondSeller = actor("SELLER");
        if (firstBankReady) verifiedBank(firstSeller, "970436", ACCOUNT_A, "TEST SHOP A");
        verifiedBank(secondSeller, "970418", ACCOUNT_B, "TEST SHOP B");
        UUID group = UUID.randomUUID(), buyer = UUID.randomUUID();
        UUID firstOrder = UUID.randomUUID(), secondOrder = UUID.randomUUID();
        UUID session =
                jdbc.queryForObject(
                        """
INSERT INTO payment_sessions(order_group_id,buyer_id,total_amount,currency,memo_code,
due_at,status,source_event_id,idempotency_key,paid_at)
VALUES (?,?,390000,'VND',?,now()+interval '1 hour','PAID',?,?,now()-interval '1 hour')
RETURNING id
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
INSERT INTO payment_transactions(session_id,provider,txn_reference,amount,status,
type,idempotency_key,received_at)
VALUES (?,'SEPAY',?,390000,'SUCCESS','NORMAL',?,now()-interval '1 hour') RETURNING id
""",
                        UUID.class,
                        session,
                        key(),
                        key());
        instruction(firstOrder, session, funding, buyer, firstSeller.id(), "150000.00");
        instruction(secondOrder, session, funding, buyer, secondSeller.id(), "240000.00");
        var first = complete(group, buyer, firstSeller, firstOrder, "150000.00", "5000.00");
        var second = complete(group, buyer, secondSeller, secondOrder, "240000.00", "10000.00");
        return new Checkout(session, funding, first, second);
    }

    private void instruction(
            UUID order, UUID session, UUID funding, UUID buyer, UUID seller, String amount) {
        jdbc.update(
                """
INSERT INTO payment_instructions(order_id,session_id,funded_transaction_id,buyer_id,
seller_id,amount,due_at,status,paid_at,last_order_version)
VALUES (?,?,?,?,?,?,now()+interval '1 hour','PAID',now()-interval '1 hour',1)
""",
                order,
                session,
                funding,
                buyer,
                seller,
                new BigDecimal(amount));
    }

    private Order complete(
            UUID group,
            UUID buyer,
            CurrentUser seller,
            UUID orderId,
            String amount,
            String shipping) {
        Instant completed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event =
                new EventEnvelope<>(
                        UUID.randomUUID(),
                        "OrderCompleted",
                        orderId.toString(),
                        completed,
                        "manual-two-shop-test",
                        new OrderCompleted(
                                orderId,
                                group,
                                buyer,
                                seller.id(),
                                "STANDARD",
                                null,
                                4,
                                completed,
                                "NX-" + orderId,
                                amount,
                                shipping));
        completions.completed(event);
        UUID payoutId =
                jdbc.queryForObject("SELECT id FROM payouts WHERE order_id=?", UUID.class, orderId);
        return new Order(payoutId, orderId, seller, event);
    }

    private void verifiedBank(CurrentUser seller, String code, String account, String holder) {
        var bank = banks.create(seller, new BankAccountRequest(code, account, holder, true, true));
        banks.verify(
                admin, bank.id(), new BankVerificationRequest("VERIFIED", null, bank.version()));
    }

    private void assertSharedCheckoutCreatesSeparatePayouts(Checkout checkout) {
        assertThat(checkout.first().payoutId()).isNotEqualTo(checkout.second().payoutId());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payouts WHERE session_id=?",
                                Long.class,
                                checkout.sessionId()))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT count(DISTINCT funded_transaction_id) FROM payment_instructions WHERE session_id=?
""",
                                Long.class,
                                checkout.sessionId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND"
                                        + " type='NORMAL'",
                                Long.class,
                                checkout.sessionId()))
                .isEqualTo(1);
        assertThat(detail(checkout.first()).executionMode()).isEqualTo("MANUAL");
        assertThat(detail(checkout.second()).executionMode()).isEqualTo("MANUAL");
    }

    private ManualPayoutView issue(Order order, CurrentUser executor) {
        var claimed =
                command(
                                executor,
                                order,
                                null,
                                "CLAIM",
                                key(),
                                new ClaimCommand(detail(order).version()))
                        .result();
        return command(
                        executor,
                        order,
                        claimed.currentAttempt().id(),
                        "ISSUE_QR",
                        key(),
                        new AttemptVersionCommand(claimed.currentAttempt().version(), null))
                .result();
    }

    private ManualPayoutView evidence(Order order, CurrentUser executor, ManualPayoutView issued) {
        return manual.upload(
                        executor,
                        order.payoutId(),
                        issued.currentAttempt().id(),
                        key(),
                        issued.currentAttempt().version(),
                        new MockMultipartFile(
                                "file",
                                "synthetic.pdf",
                                "application/pdf",
                                "%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj\n%%EOF"
                                        .getBytes(StandardCharsets.US_ASCII)))
                .result();
    }

    private ConfirmationCommand confirmation(ManualPayoutView ready) {
        return new ConfirmationCommand(
                ready.currentAttempt().version(),
                "platform-main",
                ready.netAmount(),
                "TWOSHOP" + key(),
                now(),
                ready.evidence().stream().map(ManualPayoutView.Evidence::id).toList());
    }

    private void acknowledge(Order order, ManualPayoutView settled) {
        command(
                order.seller(),
                order,
                settled.currentAttempt().id(),
                "SELLER_RECEIPT",
                key(),
                new ReceiptCommand(settled.currentAttempt().receiptVersion()));
    }

    private ManualCommandResult command(
            CurrentUser executor,
            Order order,
            UUID attempt,
            String operation,
            String idempotencyKey,
            Object body) {
        return manual.execute(
                executor, order.payoutId(), attempt, null, operation, idempotencyKey, body);
    }

    private ManualPayoutView detail(Order order) {
        return queries.detail(admin, order.payoutId());
    }

    private String payoutSnapshot(Order order) {
        return jdbc.queryForObject(
                "SELECT row_to_json(p)::text FROM payouts p WHERE id=?",
                String.class,
                order.payoutId());
    }

    private long payoutPostingCount(UUID sessionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE session_id=? AND type='PAYOUT'",
                Long.class,
                sessionId);
    }

    private void assertQr(
            Order order,
            CurrentUser executor,
            ManualPayoutView issued,
            String bank,
            String account,
            String amount)
            throws Exception {
        byte[] imageBytes = manual.qr(executor, order.payoutId(), issued.currentAttempt().id());
        var image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        var source =
                new RGBLuminanceSource(
                        image.getWidth(),
                        image.getHeight(),
                        image.getRGB(
                                0,
                                0,
                                image.getWidth(),
                                image.getHeight(),
                                null,
                                0,
                                image.getWidth()));
        String payload =
                new MultiFormatReader()
                        .decode(new BinaryBitmap(new HybridBinarizer(source)))
                        .getText();
        var fields = tlv(payload);
        var merchant = tlv(fields.get("38"));
        assertThat(tlv(merchant.get("01"))).containsEntry("00", bank).containsEntry("01", account);
        assertThat(fields).containsEntry("53", "704").containsEntry("54", amount);
        assertThat(tlv(fields.get("62"))).containsEntry("08", issued.currentAttempt().reference());
    }

    private Map<String, String> tlv(String encoded) {
        var result = new LinkedHashMap<String, String>();
        for (int position = 0; position < encoded.length(); ) {
            String tag = encoded.substring(position, position + 2);
            int length = Integer.parseInt(encoded.substring(position + 2, position + 4));
            assertThat(position + 4 + length).isLessThanOrEqualTo(encoded.length());
            assertThat(result.put(tag, encoded.substring(position + 4, position + 4 + length)))
                    .isNull();
            position += 4 + length;
        }
        return result;
    }

    private static CurrentUser actor(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record Order(
            UUID payoutId,
            UUID orderId,
            CurrentUser seller,
            EventEnvelope<OrderCompleted> completion) {}

    private record Checkout(UUID sessionId, UUID fundingId, Order first, Order second) {}
}
