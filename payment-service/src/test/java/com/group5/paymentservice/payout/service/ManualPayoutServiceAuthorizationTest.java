package com.group5.paymentservice.payout.service;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.ClaimCommand;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.PayoutVersionCommand;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.ReceiptCommand;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceValidator;
import com.group5.paymentservice.payout.qr.PayoutQrEncoder;
import com.group5.paymentservice.payout.repository.*;
import com.group5.paymentservice.sellerbank.security.BankAccountCipher;

import io.micrometer.tracing.Tracer;

import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Domain authorization is enforced without relying on controller annotations or a previously
 * authorized replay.
 */
class ManualPayoutServiceAuthorizationTest {
    private static final UUID PAYOUT = UUID.randomUUID(),
            ATTEMPT = UUID.randomUUID(),
            EVIDENCE = UUID.randomUUID(),
            SELLER = UUID.randomUUID(),
            EXECUTOR = UUID.randomUUID(),
            REVIEWER = UUID.randomUUID();
    private ManualPayoutRepository repository;
    private PayoutDisputeRepository disputes;
    private PayoutReceiptRepository receipts;
    private ManualPayoutQueryService queries;
    private PayoutEvidenceStorage storage;
    private PayoutEvidenceValidator evidenceValidator;
    private PayoutQrEncoder qr;
    private Validator validator;
    private ManualPayoutAccess access;
    private ManualPayoutService service;
    private Map<String, Object> payout;
    private Map<String, Object> attempt;

    @BeforeEach
    void setup() {
        repository = mock(ManualPayoutRepository.class);
        disputes = mock(PayoutDisputeRepository.class);
        receipts = mock(PayoutReceiptRepository.class);
        queries = mock(ManualPayoutQueryService.class);
        storage = mock(PayoutEvidenceStorage.class);
        evidenceValidator = mock(PayoutEvidenceValidator.class);
        qr = mock(PayoutQrEncoder.class);
        validator = mock(Validator.class);
        var settings = new ManualPayoutProperties();
        settings.setEnabled(true);
        access = new ManualPayoutAccess(settings);
        service =
                new ManualPayoutService(
                        repository,
                        receipts,
                        disputes,
                        queries,
                        access,
                        mock(ManualPayoutReadinessService.class),
                        settings,
                        mock(BankAccountCipher.class),
                        qr,
                        storage,
                        evidenceValidator,
                        validator,
                        JsonMapper.builder().build(),
                        mock(OutboxWriter.class),
                        mock(EventEnvelopeFactory.class),
                        mock(Tracer.class),
                        transactionManager());
        payout =
                new HashMap<>(
                        Map.of(
                                "id",
                                PAYOUT,
                                "execution_mode",
                                "MANUAL",
                                "seller_id",
                                SELLER,
                                "current_attempt_id",
                                ATTEMPT,
                                "status",
                                "PROCESSING",
                                "version",
                                1L));
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10);
        attempt =
                new HashMap<>(
                        Map.of(
                                "id",
                                ATTEMPT,
                                "payout_id",
                                PAYOUT,
                                "claimed_by",
                                EXECUTOR,
                                "state",
                                "QR_ISSUED",
                                "version",
                                1L,
                                "claim_expires_at",
                                future,
                                "qr_expires_at",
                                future));
        when(repository.payout(eq(PAYOUT), anyBoolean())).thenReturn(Optional.of(payout));
        when(repository.attempt(PAYOUT, ATTEMPT)).thenReturn(Optional.of(attempt));
        when(validator.validate(any())).thenReturn(Set.of());
    }

    @Test
    void aDowngradedAdminCannotReplayAnyFinancialOperationAsSupport() {
        // Same user ID and request key as a previous ADMIN command are insufficient after the role
        // is removed.
        var downgraded = actor(REVIEWER, "SUPPORT_STAFF");
        String oldKey = UUID.randomUUID().toString();
        for (String operation : List.of("RESOLVE", "RETRY", "REVIEW_DISPUTE")) {
            assertForbidden(
                    catchThrowable(
                            () ->
                                    service.execute(
                                            downgraded,
                                            PAYOUT,
                                            ATTEMPT,
                                            null,
                                            operation,
                                            oldKey,
                                            new ClaimCommand(0L))));
        }
        verifyNoInteractions(repository, receipts, disputes, queries, storage, validator);
    }

    @Test
    void aDowngradedAdminCannotReadAnOrdinaryCommandReplayCreatedWithAdminVisibility()
            throws Exception {
        var command = new PayoutVersionCommand(0L, null);
        String json = JsonMapper.builder().build().writeValueAsString(command);
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(json.getBytes(StandardCharsets.UTF_8)));
        String requestKey = key();
        for (String originalRole : List.of("ADMIN", "UNKNOWN")) {
            // The deliberately invalid saved JSON proves authorization fails before result
            // deserialization.
            when(repository.command(UUID.fromString(requestKey)))
                    .thenReturn(
                            Optional.of(
                                    Map.of(
                                            "actor_id",
                                            REVIEWER,
                                            "actor_role",
                                            originalRole,
                                            "payout_id",
                                            PAYOUT,
                                            "operation",
                                            "READINESS_CHECK",
                                            "payload_hash",
                                            digest,
                                            "result",
                                            "not-valid-result-json")));
            var error =
                    catchThrowable(
                            () ->
                                    service.execute(
                                            actor(REVIEWER, "SUPPORT_STAFF"),
                                            PAYOUT,
                                            null,
                                            null,
                                            "READINESS_CHECK",
                                            requestKey,
                                            command));
            assertForbidden(error);
        }
        verify(repository, never()).updatePayout(any(), anyMap());
        verify(repository, never()).updateAttempt(any(), anyMap());
        verify(repository, never())
                .recordCommand(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString());
        verifyNoInteractions(queries, storage, receipts, disputes);
    }

    @Test
    void fakePrivilegesAndAnonymousCallsCannotReachPersistenceOrReplay() {
        for (CurrentUser actor :
                List.of(actor(REVIEWER), actor(REVIEWER, "BUYER"), actor(REVIEWER, "SELLER"))) {
            assertForbidden(
                    catchThrowable(
                            () ->
                                    service.execute(
                                            actor,
                                            PAYOUT,
                                            null,
                                            null,
                                            "CLAIM",
                                            key(),
                                            new ClaimCommand(0L))));
        }
        var anonymous =
                catchThrowable(
                        () ->
                                service.execute(
                                        null,
                                        PAYOUT,
                                        null,
                                        null,
                                        "CLAIM",
                                        key(),
                                        new ClaimCommand(0L)));
        assertDomain(anonymous, 401, UNAUTHENTICATED.code());
        verifyNoInteractions(repository, receipts, disputes, queries, storage, validator);
    }

    @Test
    void sellerOwnershipIsCheckedBeforeReturningAPreviouslySavedResult() {
        var wrongSeller = actor(REVIEWER, "SELLER");
        var error =
                catchThrowable(
                        () ->
                                service.execute(
                                        wrongSeller,
                                        PAYOUT,
                                        ATTEMPT,
                                        null,
                                        "SELLER_RECEIPT",
                                        key(),
                                        new ReceiptCommand(0L)));
        assertDomain(error, 404, MANUAL_PAYOUT_NOT_FOUND.code());
        verify(repository, never()).command(any());
        verify(repository, never())
                .recordCommand(
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString());
        verifyNoInteractions(storage, queries, receipts, disputes);
    }

    @Test
    void mixedRoleOwnersCanAcknowledgeAsSellerButCannotExecuteTheirOwnPayout() {
        for (String staffRole : List.of("ADMIN", "SUPPORT_STAFF")) {
            var mixed = actor(SELLER, staffRole, "SELLER");
            assertThatCode(() -> access.seller(mixed, payout)).doesNotThrowAnyException();
            assertForbidden(
                    catchThrowable(
                            () ->
                                    service.execute(
                                            mixed,
                                            PAYOUT,
                                            null,
                                            null,
                                            "CLAIM",
                                            key(),
                                            new ClaimCommand(0L))));
            assertForbidden(catchThrowable(() -> service.qr(mixed, PAYOUT, ATTEMPT)));
            assertThat(access.actions(mixed, payout, attempt, true, true)).isEmpty();
        }
        assertForbidden(catchThrowable(() -> access.seller(actor(SELLER, "ADMIN"), payout)));
        verify(repository, never()).command(any());
        verifyNoInteractions(qr, storage, queries, receipts, disputes);
    }

    @Test
    void anotherAdminHasNoOwnershipBypassForAnExecutorsLiveQr() {
        var error = catchThrowable(() -> service.qr(actor(REVIEWER, "ADMIN"), PAYOUT, ATTEMPT));
        assertDomain(error, 409, MANUAL_PAYOUT_CONFLICT.code());
        assertThat(access.actions(actor(REVIEWER, "ADMIN"), payout, attempt, true, true))
                .doesNotContain("READ_QR", "CONFIRM_TRANSFER");
        assertThat(access.actions(actor(EXECUTOR, "SUPPORT_STAFF"), payout, attempt, false, false))
                .contains("READ_QR", "CONFIRM_TRANSFER");
        verifyNoInteractions(qr, storage);
        verify(repository, never()).updateAttempt(any(), anyMap());
    }

    @Test
    void staffQueueAccessDoesNotGrantAnotherExecutorsPrivateProof() {
        assertForbidden(
                catchThrowable(
                        () ->
                                service.evidence(
                                        actor(REVIEWER, "SUPPORT_STAFF"),
                                        PAYOUT,
                                        ATTEMPT,
                                        EVIDENCE)));
        verify(repository, never()).evidence(PAYOUT, ATTEMPT, EVIDENCE);
        verifyNoInteractions(storage, disputes);
    }

    @Test
    void adminEvidenceAccessRequiresAReconciliationCaseAndMatchingIds() {
        var admin = actor(REVIEWER, "ADMIN");
        when(disputes.cases(PAYOUT)).thenReturn(List.of());
        assertDomain(
                catchThrowable(() -> service.evidence(admin, PAYOUT, ATTEMPT, EVIDENCE)),
                409,
                MANUAL_PAYOUT_CONFLICT.code());
        verifyNoInteractions(storage);
        when(disputes.cases(PAYOUT))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "attempt_id", ATTEMPT)));
        var bytes =
                new PayoutEvidenceStorage.StoredEvidence(new byte[] {1, 2, 3}, "application/pdf");
        when(repository.evidence(PAYOUT, ATTEMPT, EVIDENCE))
                .thenReturn(Optional.of(metadata("private-test-object", bytes)));
        when(storage.get("private-test-object")).thenReturn(bytes);
        when(evidenceValidator.validate(bytes.content(), bytes.contentType()))
                .thenReturn(
                        new PayoutEvidenceValidator.ValidatedEvidence(
                                bytes.content(), bytes.contentType(), "pdf", "0".repeat(64)));
        assertThat(service.evidence(admin, PAYOUT, ATTEMPT, EVIDENCE)).isSameAs(bytes);
        UUID mismatchedEvidence = UUID.randomUUID();
        when(repository.evidence(PAYOUT, ATTEMPT, mismatchedEvidence)).thenReturn(Optional.empty());
        assertDomain(
                catchThrowable(() -> service.evidence(admin, PAYOUT, ATTEMPT, mismatchedEvidence)),
                404,
                MANUAL_PAYOUT_NOT_FOUND.code());
        verify(storage, times(1)).get(anyString());
    }

    @Test
    void theOwningExecutorCanReadTheirProofWithoutAReviewCase() {
        var bytes = new PayoutEvidenceStorage.StoredEvidence(new byte[] {1}, "image/png");
        when(repository.evidence(PAYOUT, ATTEMPT, EVIDENCE))
                .thenReturn(Optional.of(metadata("owned-test-object", bytes)));
        when(storage.get("owned-test-object")).thenReturn(bytes);
        when(evidenceValidator.validate(bytes.content(), bytes.contentType()))
                .thenReturn(
                        new PayoutEvidenceValidator.ValidatedEvidence(
                                bytes.content(), bytes.contentType(), "png", "0".repeat(64)));
        assertThat(service.evidence(actor(EXECUTOR, "SUPPORT_STAFF"), PAYOUT, ATTEMPT, EVIDENCE))
                .isSameAs(bytes);
        verifyNoInteractions(disputes);
    }

    @Test
    void aDowngradedAdminCannotReplayAnUploadThatOnlyTheirOldReviewRoleAuthorized() {
        var file = new MockMultipartFile("file", "proof.png", "image/png", new byte[] {1});
        when(evidenceValidator.validate(file))
                .thenReturn(
                        new PayoutEvidenceValidator.ValidatedEvidence(
                                new byte[] {1}, "image/png", "png", "0".repeat(64)));
        var error =
                catchThrowable(
                        () ->
                                service.upload(
                                        actor(REVIEWER, "SUPPORT_STAFF"),
                                        PAYOUT,
                                        ATTEMPT,
                                        key(),
                                        0L,
                                        file));
        assertForbidden(error);
        verify(repository, never()).command(any());
        verifyNoInteractions(storage);
    }

    @Test
    void corruptedStoredEvidenceIsAStorageFailureRatherThanAnUploadValidationError() {
        var stored = new PayoutEvidenceStorage.StoredEvidence(new byte[] {9, 9, 9}, "image/png");
        when(repository.evidence(PAYOUT, ATTEMPT, EVIDENCE))
                .thenReturn(Optional.of(metadata("corrupt-test-object", stored)));
        when(storage.get("corrupt-test-object")).thenReturn(stored);
        when(evidenceValidator.validate(stored.content(), stored.contentType()))
                .thenThrow(
                        new DomainException(
                                com.group5.paymentservice.payout.evidence.PayoutEvidenceErrorCode
                                        .EVIDENCE_UNSUPPORTED));
        var error =
                catchThrowable(
                        () ->
                                service.evidence(
                                        actor(EXECUTOR, "SUPPORT_STAFF"),
                                        PAYOUT,
                                        ATTEMPT,
                                        EVIDENCE));
        assertDomain(error, 503, MANUAL_PAYOUT_UNAVAILABLE.code());
        verify(repository, never()).updateAttempt(any(), anyMap());
        verify(repository, never()).updatePayout(any(), anyMap());
    }

    private static CurrentUser actor(UUID id, String... roles) {
        return new CurrentUser(id, Set.of(roles), Set.of("ORDER.REFUND", "MANUAL_PAYOUT.EXECUTE"));
    }

    private static org.springframework.transaction.PlatformTransactionManager transactionManager() {
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any()))
                .thenAnswer(
                        call ->
                                new org.springframework.transaction.support
                                        .SimpleTransactionStatus());
        return manager;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> metadata(
            String key, PayoutEvidenceStorage.StoredEvidence bytes) {
        return Map.of(
                "object_key",
                key,
                "bucket",
                "payout-evidence",
                "digest",
                "0".repeat(64),
                "size_bytes",
                (long) bytes.content().length,
                "content_type",
                bytes.contentType());
    }

    private static void assertForbidden(Throwable error) {
        assertDomain(error, 403, MANUAL_PAYOUT_FORBIDDEN.code());
    }

    private static void assertDomain(Throwable error, int status, String code) {
        assertThat(error)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        e -> {
                            assertThat(e.getCode()).isEqualTo(status);
                            assertThat(e.getErrorCode()).isEqualTo(code);
                        });
    }
}
