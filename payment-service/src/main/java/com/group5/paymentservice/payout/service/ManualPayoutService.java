package com.group5.paymentservice.payout.service;

import static com.group5.paymentservice.payout.service.ManualPayoutQueryService.*;
import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.*;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.*;
import com.group5.paymentservice.payout.evidence.*;
import com.group5.paymentservice.payout.model.ManualTransferSnapshot;
import com.group5.paymentservice.payout.qr.PayoutQrEncoder;
import com.group5.paymentservice.payout.repository.*;
import com.group5.paymentservice.sellerbank.security.BankAccountCipher;

import io.micrometer.tracing.Tracer;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * Commands serialize on payment-group/bank/payout in that order; no bank-transfer provider is
 * called.
 */
@Service
@RequiredArgsConstructor
public class ManualPayoutService {
    private final ManualPayoutRepository repository;
    private final PayoutReceiptRepository receipts;
    private final PayoutDisputeRepository disputes;
    private final ManualPayoutQueryService queries;
    private final ManualPayoutAccess access;
    private final ManualPayoutReadinessService readiness;
    private final ManualPayoutProperties settings;
    private final BankAccountCipher cipher;
    private final PayoutQrEncoder qr;
    private final PayoutEvidenceStorage storage;
    private final PayoutEvidenceValidator evidenceValidator;
    private final Validator validator;
    private final JsonMapper mapper;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Tracer tracer;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private static final UUID SYSTEM = new UUID(0, 0);

    @Transactional
    public ManualCommandResult execute(
            CurrentUser actor,
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String operation,
            String key,
            Object body) {
        boolean seller = operation.startsWith("SELLER_");
        if (seller) {
            access.identity(actor);
            if (!actor.hasRole("SELLER")) throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
        } else access.staff(actor);
        if (Set.of("RESOLVE", "RETRY", "REVIEW_DISPUTE").contains(operation)) access.admin(actor);
        if (body == null || !validator.validate(body).isEmpty())
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        UUID requestKey = requestKey(key);
        String hash = hash(mapper.writeValueAsString(body));
        repository.lockKey("manual-command:" + requestKey);
        var p = lock(payoutId);
        if (seller) access.seller(actor, p);
        else access.noSelfPayment(actor, p);
        var replay = replay(actor, payoutId, attemptId, caseId, operation, requestKey, hash);
        if (replay != null) return replay;
        var a =
                attemptId == null
                        ? null
                        : repository
                                .attempt(payoutId, attemptId)
                                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        if (a != null && !attemptId.equals(p.get("current_attempt_id")))
            throw new DomainException(MANUAL_PAYOUT_CONFLICT);
        boolean conflict = false;
        boolean created = false;
        switch (operation) {
            case "READINESS_CHECK" -> {
                var command = (PayoutVersionCommand) body;
                version(p, command.expectedPayoutVersion());
                require(Set.of("ON_HOLD", "PENDING").contains(str(p, "status")));
                String blocked = blocked(p);
                changePayout(
                        p,
                        fields(
                                "status",
                                blocked == null ? "PENDING" : "ON_HOLD",
                                "blocked_reason",
                                blocked,
                                "updated_by",
                                actor.id()));
                audit(p, null, null, "READINESS_CHECKED", actor, command.reason(), false);
            }
            case "CLAIM" -> {
                var command = (ClaimCommand) body;
                intake();
                version(p, command.expectedPayoutVersion());
                require("PENDING".equals(p.get("status")));
                String blocked = blocked(p);
                if (blocked != null) {
                    changePayout(
                            p,
                            fields(
                                    "status",
                                    "ON_HOLD",
                                    "blocked_reason",
                                    blocked,
                                    "updated_by",
                                    actor.id()));
                    audit(p, null, null, "HELD", actor, "Readiness changed before claim", false);
                    conflict = true;
                    break;
                }
                var bank =
                        repository
                                .bank(id(p, "seller_id"))
                                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_CONFLICT));
                UUID attempt = UUID.randomUUID();
                int number = number(p, "attempt_count").intValue() + 1;
                repository.insertAttempt(
                        fields(
                                "id",
                                attempt,
                                "payout_id",
                                payoutId,
                                "attempt_no",
                                number,
                                "state",
                                "CLAIMED",
                                "claimed_by",
                                actor.id(),
                                "seller_bank_id",
                                bank.get("id"),
                                "bank_version",
                                bank.get("version"),
                                "bank_code",
                                bank.get("bank_code"),
                                "account_encrypted",
                                bank.get("account_number_encrypted"),
                                "account_last4",
                                bank.get("account_last4"),
                                "holder_name",
                                bank.get("account_holder_name"),
                                "net_amount",
                                p.get("net_amount"),
                                "currency",
                                p.get("currency"),
                                "claim_expires_at",
                                now().plus(settings.getClaimLease()),
                                "reference",
                                "NXP" + attempt.toString().replace("-", "").substring(0, 20)));
                changePayout(
                        p,
                        fields(
                                "status",
                                "PROCESSING",
                                "current_attempt_id",
                                attempt,
                                "attempt_count",
                                number,
                                "blocked_reason",
                                null,
                                "updated_by",
                                actor.id(),
                                "seller_bank_id",
                                bank.get("id"),
                                "recipient_bank_code",
                                bank.get("bank_code"),
                                "recipient_account_encrypted",
                                bank.get("account_number_encrypted"),
                                "recipient_account_last4",
                                bank.get("account_last4"),
                                "recipient_holder_name",
                                bank.get("account_holder_name")));
                audit(p, attempt, null, "MANUAL_CLAIMED", actor, null, false);
                created = true;
            }
            case "ISSUE_QR" -> {
                var command = (AttemptVersionCommand) body;
                intake();
                access.owner(actor, a);
                version(a, command.expectedAttemptVersion());
                require("CLAIMED".equals(a.get("state")));
                if (!time(a, "claim_expires_at").isAfter(now())) {
                    release(p, a, actor, "Claim expired before issuance");
                    conflict = true;
                    break;
                }
                if (blocked(p) != null || !sameBank(p, a)) {
                    release(p, a, actor, "Readiness changed before QR issuance");
                    conflict = true;
                    break;
                }
                // Validate/encode before committing issuance; a GET only re-encodes this same
                // snapshot.
                encode(a);
                repository.updateAttempt(
                        attemptId,
                        fields(
                                "state",
                                "QR_ISSUED",
                                "qr_issued_at",
                                now(),
                                "qr_expires_at",
                                now().plus(settings.getQrDeadline())));
                audit(p, attemptId, null, "QR_ISSUED", actor, null, false);
                created = true;
            }
            case "RELEASE" -> {
                var command = (AttemptVersionCommand) body;
                version(a, command.expectedAttemptVersion());
                require("CLAIMED".equals(a.get("state")));
                if (!actor.id().equals(a.get("claimed_by"))) {
                    access.admin(actor);
                    text(command.reason());
                }
                release(p, a, actor, command.reason());
            }
            case "CONFIRM_TRANSFER" -> {
                var command = (ConfirmationCommand) body;
                access.owner(actor, a);
                version(a, command.expectedAttemptVersion());
                require("QR_ISSUED".equals(a.get("state")));
                if (!time(a, "qr_expires_at").isAfter(now())
                        || blocked(p) != null
                        || !sameBank(p, a)) {
                    uncertain(p, a, actor, "Issuance expired or bank/funding changed");
                    conflict = true;
                    break;
                }
                confirm(
                        p,
                        a,
                        actor,
                        command.sourceAccountId(),
                        command.amount(),
                        command.bankTransferReference(),
                        command.transferredAt(),
                        command.evidenceIds(),
                        false);
            }
            case "REPORT_PROBLEM" -> {
                var command = (ProblemCommand) body;
                access.owner(actor, a);
                version(a, command.expectedAttemptVersion());
                text(command.reason());
                require(Set.of("QR_ISSUED", "UNKNOWN", "SUCCEEDED").contains(str(a, "state")));
                boundEvidence(payoutId, attemptId, command.evidenceIds(), actor, false);
                UUID review = UUID.randomUUID();
                disputes.insertCase(
                        fields(
                                "id",
                                review,
                                "payout_id",
                                payoutId,
                                "attempt_id",
                                attemptId,
                                "kind",
                                "STAFF_EXCEPTION",
                                "status",
                                "OPEN",
                                "reason",
                                "STAFF_REPORTED_PROBLEM",
                                "explanation",
                                command.reason().trim(),
                                "reported_by",
                                actor.id()));
                repository.attachEvidence(
                        review,
                        true,
                        command.evidenceIds() == null ? List.of() : command.evidenceIds());
                if (!"SUCCEEDED".equals(a.get("state")))
                    uncertain(p, a, actor, "Staff reported uncertainty");
                else repository.updateAttempt(attemptId, fields("state", "SUCCEEDED"));
                audit(p, attemptId, review, "MANUAL_PROBLEM", actor, command.reason(), false);
                created = true;
            }
            case "RESOLVE" -> {
                access.admin(actor);
                var command = (ResolutionCommand) body;
                version(p, command.expectedPayoutVersion());
                version(a, command.expectedAttemptVersion());
                text(command.reason());
                var review =
                        disputes.reviewCase(payoutId, attemptId, command.caseId())
                                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
                version(review, command.expectedCaseVersion());
                require(!"RESOLVED".equals(review.get("status")));
                boundEvidence(payoutId, attemptId, command.evidenceIds(), actor, true);
                repository.attachEvidence(command.caseId(), true, command.evidenceIds());
                switch (command.outcome()) {
                    case CONFIRMED_SENT -> {
                        require("UNKNOWN".equals(a.get("state")));
                        confirm(
                                p,
                                a,
                                actor,
                                command.sourceAccountId(),
                                command.amount(),
                                command.bankTransferReference(),
                                command.transferredAt(),
                                command.evidenceIds(),
                                true);
                    }
                    case CONFIRMED_NOT_SENT -> {
                        require("UNKNOWN".equals(a.get("state")));
                        repository.updateAttempt(attemptId, fields("state", "NOT_SENT"));
                        changePayout(
                                p,
                                fields(
                                        "status",
                                        "FAILED",
                                        "blocked_reason",
                                        null,
                                        "updated_by",
                                        actor.id()));
                        audit(
                                p,
                                attemptId,
                                command.caseId(),
                                "FAILED",
                                actor,
                                command.reason(),
                                false);
                    }
                    case CONFIRMED_RETURNED -> {
                        require(
                                "SUCCEEDED".equals(a.get("state"))
                                        && "SUCCEEDED".equals(p.get("status"))
                                        && a.get("transaction_id") != null);
                        UUID tx =
                                repository.movement(
                                        payoutId,
                                        attemptId,
                                        "PAYOUT_RETURN",
                                        id(a, "transaction_id"));
                        repository.updateAttempt(
                                attemptId,
                                fields("state", "RETURNED", "return_transaction_id", tx));
                        changePayout(
                                p,
                                fields(
                                        "status",
                                        "RETURNED",
                                        "return_transaction_id",
                                        tx,
                                        "returned_at",
                                        now(),
                                        "updated_by",
                                        actor.id()));
                        audit(
                                p,
                                attemptId,
                                command.caseId(),
                                "RETURNED",
                                actor,
                                command.reason(),
                                false);
                        publish(p, a, "PayoutReturned", tx);
                    }
                    case CONFIRMATION_RECORDED_IN_ERROR -> {
                        require(
                                "SUCCEEDED".equals(a.get("state"))
                                        && "SUCCEEDED".equals(p.get("status"))
                                        && a.get("transaction_id") != null);
                        UUID tx =
                                repository.movement(
                                        payoutId,
                                        attemptId,
                                        "PAYOUT_CORRECTION",
                                        id(a, "transaction_id"));
                        repository.updateAttempt(
                                attemptId,
                                fields(
                                        "state",
                                        "CONFIRMATION_CORRECTED",
                                        "correction_transaction_id",
                                        tx));
                        changePayout(
                                p,
                                fields(
                                        "status",
                                        "FAILED",
                                        "blocked_reason",
                                        "CONFIRMATION_CORRECTED",
                                        "updated_by",
                                        actor.id()));
                        audit(
                                p,
                                attemptId,
                                command.caseId(),
                                "CONFIRMATION_CORRECTED",
                                actor,
                                command.reason(),
                                false);
                        publish(p, a, "PayoutConfirmationCorrected", tx);
                    }
                }
                // The one reviewed financial disposition applies to every active case on this
                // attempt.
                for (var related : disputes.cases(payoutId))
                    if (attemptId.equals(related.get("attempt_id"))
                            && !"RESOLVED".equals(related.get("status"))) {
                        closeCase(related, actor, command.outcome().name());
                        audit(
                                p,
                                attemptId,
                                id(related, "id"),
                                "MANUAL_RESOLVED",
                                actor,
                                command.reason(),
                                false);
                    }
            }
            case "REVIEW_DISPUTE" -> {
                access.admin(actor);
                var command = (ReviewCommand) body;
                version(a, command.expectedAttemptVersion());
                text(command.reason());
                var review =
                        disputes.reviewCase(payoutId, attemptId, caseId)
                                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
                version(review, command.expectedCaseVersion());
                require(
                        "SELLER_DISPUTE".equals(review.get("kind"))
                                && !"RESOLVED".equals(review.get("status")));
                boundEvidence(payoutId, attemptId, command.evidenceIds(), actor, true);
                if (command.action() == ReviewAction.TRANSFER_EVIDENCE_PROVIDED)
                    require(command.evidenceIds() != null && !command.evidenceIds().isEmpty());
                disputes.updateCase(
                        caseId,
                        fields(
                                "status",
                                command.action() == ReviewAction.REQUEST_INFORMATION
                                        ? "UNDER_REVIEW"
                                        : "WAITING_SELLER",
                                "reviewed_by",
                                actor.id()));
                if (command.evidenceIds() != null && !command.evidenceIds().isEmpty())
                    repository.attachEvidence(caseId, true, command.evidenceIds());
                audit(p, attemptId, caseId, "DISPUTE_REVIEWED", actor, command.reason(), false);
            }
            case "RETRY" -> {
                access.admin(actor);
                var command = (PayoutVersionCommand) body;
                version(p, command.expectedPayoutVersion());
                text(command.reason());
                require(Set.of("FAILED", "RETURNED").contains(str(p, "status")));
                require(!openCase(payoutId));
                var current =
                        repository
                                .currentAttempt(payoutId)
                                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_CONFLICT));
                require(
                        Set.of("NOT_SENT", "RETURNED", "CONFIRMATION_CORRECTED")
                                .contains(str(current, "state")));
                String blocked = blocked(p);
                changePayout(
                        p,
                        fields(
                                "status",
                                blocked == null ? "PENDING" : "ON_HOLD",
                                "blocked_reason",
                                blocked,
                                "updated_by",
                                actor.id()));
                audit(p, id(current, "id"), null, "RETRIED", actor, command.reason(), false);
            }
            case "SELLER_RECEIPT" -> {
                var command = (ReceiptCommand) body;
                sellerVersion(p, a, command.expectedReceiptVersion());
                require(!"CONFIRMED".equals(a.get("receipt_status")));
                receipts.updateReceipt(attemptId, "CONFIRMED", now());
                for (var review : disputes.cases(payoutId))
                    if (attemptId.equals(review.get("attempt_id"))
                            && "SELLER_DISPUTE".equals(review.get("kind"))
                            && !"RESOLVED".equals(review.get("status")))
                        closeCase(review, actor, "SELLER_CONFIRMED_RECEIPT");
                audit(p, attemptId, null, "RECEIPT_CONFIRMED", actor, null, true);
            }
            case "SELLER_DISPUTE" -> {
                var command = (DisputeCommand) body;
                sellerVersion(p, a, command.expectedReceiptVersion());
                text(command.explanation());
                if ("CONFIRMED".equals(a.get("receipt_status")))
                    require(command.reason() == DisputeReason.CONFIRMATION_MISTAKE);
                var active =
                        disputes.cases(payoutId).stream()
                                .filter(
                                        c ->
                                                attemptId.equals(c.get("attempt_id"))
                                                        && "SELLER_DISPUTE".equals(c.get("kind"))
                                                        && !"RESOLVED".equals(c.get("status")))
                                .findFirst();
                UUID disputeId = active.map(c -> id(c, "id")).orElseGet(UUID::randomUUID);
                if (active.isEmpty()) {
                    disputes.insertCase(
                            fields(
                                    "id",
                                    disputeId,
                                    "payout_id",
                                    payoutId,
                                    "attempt_id",
                                    attemptId,
                                    "kind",
                                    "SELLER_DISPUTE",
                                    "status",
                                    "OPEN",
                                    "reason",
                                    command.reason().name(),
                                    "explanation",
                                    command.explanation().trim(),
                                    "reported_by",
                                    actor.id()));
                    created = true;
                }
                receipts.updateReceipt(attemptId, "DISPUTED", null);
                audit(
                        p,
                        attemptId,
                        disputeId,
                        "RECEIPT_DISPUTED",
                        actor,
                        command.explanation(),
                        true);
            }
            default -> throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        }
        var result =
                new ManualCommandResult(
                        queries.view(actor, payoutId, seller), false, created, conflict);
        repository.recordCommand(
                requestKey,
                actor.id(),
                operation,
                payoutId,
                attemptId,
                caseId,
                hash,
                mapper.writeValueAsString(result),
                seller ? "SELLER" : actor.hasRole("ADMIN") ? "ADMIN" : "SUPPORT_STAFF");
        return result;
    }

    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ManualCommandResult upload(
            CurrentUser actor,
            UUID payoutId,
            UUID attemptId,
            String key,
            Long expectedVersion,
            MultipartFile file) {
        access.staff(actor);
        if (expectedVersion == null || expectedVersion < 0)
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        var validated = evidenceValidator.validate(file);
        UUID requestKey = requestKey(key);
        String hash =
                hash(expectedVersion + ":" + validated.contentType() + ":" + validated.sha256());
        var transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setTimeout(10);
        transaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var previous =
                transaction.execute(
                        status ->
                                authorizeUpload(
                                        actor,
                                        payoutId,
                                        attemptId,
                                        requestKey,
                                        hash,
                                        expectedVersion));
        if (previous != null) return previous;
        if (!storage.configured()) throw new DomainException(MANUAL_PAYOUT_UNAVAILABLE);
        UUID evidenceId = UUID.randomUUID();
        String objectKey =
                payoutId + "/" + attemptId + "/" + evidenceId + "." + validated.extension();
        boolean committed = false;
        try {
            // No database transaction or financial row lock is held while waiting for Supabase.
            storage.put(objectKey, validated.content(), validated.contentType());
            var result =
                    transaction.execute(
                            status -> {
                                var replay =
                                        authorizeUpload(
                                                actor,
                                                payoutId,
                                                attemptId,
                                                requestKey,
                                                hash,
                                                expectedVersion);
                                if (replay != null) return replay;
                                var p = repository.payout(payoutId, false).orElseThrow();
                                var a = repository.attempt(payoutId, attemptId).orElseThrow();
                                repository.insertEvidence(
                                        fields(
                                                "id",
                                                evidenceId,
                                                "payout_id",
                                                payoutId,
                                                "attempt_id",
                                                attemptId,
                                                "uploader_id",
                                                actor.id(),
                                                "bucket",
                                                settings.getEvidence().getBucket(),
                                                "object_key",
                                                objectKey,
                                                "content_type",
                                                validated.contentType(),
                                                "size_bytes",
                                                validated.size(),
                                                "digest",
                                                validated.sha256()));
                                repository.updateAttempt(
                                        attemptId, fields("state", a.get("state")));
                                audit(p, attemptId, null, "EVIDENCE_UPLOADED", actor, null, false);
                                var response =
                                        new ManualCommandResult(
                                                queries.view(actor, payoutId, false),
                                                false,
                                                true,
                                                false);
                                repository.recordCommand(
                                        requestKey,
                                        actor.id(),
                                        "UPLOAD_EVIDENCE",
                                        payoutId,
                                        attemptId,
                                        null,
                                        hash,
                                        mapper.writeValueAsString(response),
                                        actor.hasRole("ADMIN") ? "ADMIN" : "SUPPORT_STAFF");
                                return response;
                            });
            committed = result != null && !result.replay();
            return Objects.requireNonNull(result);
        } finally {
            if (!committed)
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException ignored) {
                    // The private-bucket orphan scanner also recovers crashes and failed
                    // compensation.
                }
        }
    }

    private ManualCommandResult authorizeUpload(
            CurrentUser actor,
            UUID payoutId,
            UUID attemptId,
            UUID requestKey,
            String hash,
            Long expectedVersion) {
        repository.lockKey("manual-command:" + requestKey);
        var p = lock(payoutId);
        access.noSelfPayment(actor, p);
        var a =
                repository
                        .attempt(payoutId, attemptId)
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        evidenceAccess(actor, p, a);
        var replay = replay(actor, payoutId, attemptId, null, "UPLOAD_EVIDENCE", requestKey, hash);
        if (replay != null) return replay;
        require(attemptId.equals(p.get("current_attempt_id")));
        version(a, expectedVersion);
        require(Set.of("QR_ISSUED", "UNKNOWN", "SUCCEEDED").contains(str(a, "state")));
        require(repository.evidence(attemptId).size() < 3);
        return null;
    }

    @Transactional(readOnly = true)
    public byte[] qr(CurrentUser actor, UUID payoutId, UUID attemptId) {
        access.staff(actor);
        var p = manual(payoutId);
        access.noSelfPayment(actor, p);
        var a =
                repository
                        .attempt(payoutId, attemptId)
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        access.owner(actor, a);
        require(
                attemptId.equals(p.get("current_attempt_id"))
                        && "QR_ISSUED".equals(a.get("state"))
                        && time(a, "qr_expires_at").isAfter(now()));
        return encode(a);
    }

    public PayoutEvidenceStorage.StoredEvidence evidence(
            CurrentUser actor, UUID payoutId, UUID attemptId, UUID evidenceId) {
        access.staff(actor);
        var p = manual(payoutId);
        access.noSelfPayment(actor, p);
        var a =
                repository
                        .attempt(payoutId, attemptId)
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        evidenceAccess(actor, p, a);
        var e =
                repository
                        .evidence(payoutId, attemptId, evidenceId)
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        if (!settings.getEvidence().getBucket().equals(e.get("bucket")))
            throw new DomainException(MANUAL_PAYOUT_UNAVAILABLE);
        var stored = storage.get(str(e, "object_key"));
        PayoutEvidenceValidator.ValidatedEvidence verified;
        try {
            verified = evidenceValidator.validate(stored.content(), stored.contentType());
        } catch (DomainException corruptedStorageObject) {
            throw new DomainException(MANUAL_PAYOUT_UNAVAILABLE);
        }
        if (!verified.sha256().equals(e.get("digest"))
                || verified.size() != number(e, "size_bytes").longValue()
                || !verified.contentType().equals(e.get("content_type")))
            throw new DomainException(MANUAL_PAYOUT_UNAVAILABLE);
        return stored;
    }

    @Transactional(timeout = 10)
    public void expire(UUID payoutId) {
        var p = lock(payoutId);
        var a = repository.currentAttempt(payoutId).orElse(null);
        if (a == null) return;
        var actor = new CurrentUser(SYSTEM, Set.of("ADMIN"), Set.of());
        if ("CLAIMED".equals(a.get("state")) && !time(a, "claim_expires_at").isAfter(now()))
            release(p, a, actor, "Claim lease expired");
        if ("QR_ISSUED".equals(a.get("state"))
                && (!time(a, "qr_expires_at").isAfter(now()) || !sameBank(p, a)))
            uncertain(p, a, actor, "QR deadline expired or reviewed bank changed");
    }

    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recheckBankAfterCommit(UUID payoutId) {
        var p = lock(payoutId);
        var a = repository.currentAttempt(payoutId).orElse(null);
        if (a != null && "QR_ISSUED".equals(a.get("state")) && !sameBank(p, a))
            uncertain(
                    p,
                    a,
                    new CurrentUser(SYSTEM, Set.of("ADMIN"), Set.of()),
                    "Reviewed bank changed after QR issuance");
    }

    private Map<String, Object> manual(UUID id) {
        return repository
                .payout(id, false)
                .filter(p -> "MANUAL".equals(p.get("execution_mode")))
                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
    }

    private Map<String, Object> lock(UUID payoutId) {
        var p = manual(payoutId);
        repository.lockPayoutContext(p);
        return repository
                .payout(payoutId, true)
                .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
    }

    private ManualCommandResult replay(
            CurrentUser actor,
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String operation,
            UUID key,
            String hash) {
        var saved = repository.command(key);
        if (saved.isEmpty()) return null;
        var row = saved.get();
        if (!actor.id().equals(row.get("actor_id"))
                || !payoutId.equals(row.get("payout_id"))
                || !Objects.equals(attemptId, row.get("attempt_id"))
                || !Objects.equals(caseId, row.get("case_id"))
                || !operation.equals(row.get("operation"))
                || !hash.equals(row.get("payload_hash")))
            throw new DomainException(MANUAL_PAYOUT_IDEMPOTENCY_CONFLICT);
        String originalRole = str(row, "actor_role");
        if (("ADMIN".equals(originalRole) || "UNKNOWN".equals(originalRole))
                && !actor.hasRole("ADMIN")) throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
        var original = mapper.readValue(row.get("result").toString(), ManualCommandResult.class);
        return new ManualCommandResult(
                original.result(), true, original.created(), original.conflict());
    }

    private String blocked(Map<String, Object> p) {
        return readiness.blockedReason(p);
    }

    private boolean sameBank(Map<String, Object> p, Map<String, Object> a) {
        var bank = repository.bank(id(p, "seller_id"));
        if (bank.isEmpty()) return false;
        var b = bank.get();
        var snapshot = ManualTransferSnapshot.fromAttempt(a);
        return b.get("id").equals(snapshot.bankId())
                && number(b, "version").longValue() == snapshot.bankVersion()
                && Objects.equals(b.get("bank_code"), snapshot.bankCode())
                && Objects.equals(b.get("account_holder_name"), snapshot.holderName())
                && cipher.decrypt(str(b, "account_number_encrypted"))
                        .equals(cipher.decrypt(snapshot.encryptedAccount()));
    }

    private void release(
            Map<String, Object> p, Map<String, Object> a, CurrentUser actor, String reason) {
        require(a.get("qr_issued_at") == null);
        repository.updateAttempt(id(a, "id"), fields("state", "NOT_SENT"));
        String blocked = blocked(p);
        changePayout(
                p,
                fields(
                        "status",
                        blocked == null ? "PENDING" : "ON_HOLD",
                        "blocked_reason",
                        blocked,
                        "updated_by",
                        actor.id()));
        audit(p, id(a, "id"), null, "CLAIM_RELEASED", actor, reason, false);
    }

    private void uncertain(
            Map<String, Object> p, Map<String, Object> a, CurrentUser actor, String reason) {
        repository.updateAttempt(id(a, "id"), fields("state", "UNKNOWN"));
        changePayout(
                p,
                fields(
                        "status",
                        "UNKNOWN",
                        "blocked_reason",
                        "RECONCILIATION_REQUIRED",
                        "updated_by",
                        actor.id()));
        if (!openCase(id(p, "id"))) {
            UUID review = UUID.randomUUID();
            disputes.insertCase(
                    fields(
                            "id",
                            review,
                            "payout_id",
                            p.get("id"),
                            "attempt_id",
                            a.get("id"),
                            "kind",
                            "STAFF_EXCEPTION",
                            "status",
                            "OPEN",
                            "reason",
                            "RECONCILIATION_REQUIRED",
                            "explanation",
                            reason,
                            "reported_by",
                            actor.id()));
        }
        audit(p, id(a, "id"), null, "MANUAL_PROBLEM", actor, reason, false);
    }

    private void confirm(
            Map<String, Object> p,
            Map<String, Object> a,
            CurrentUser actor,
            String source,
            String amount,
            String reference,
            OffsetDateTime transferred,
            List<UUID> evidence,
            boolean resolution) {
        require(source != null && settings.isAuthorizedSourceAccount(source));
        require(
                amount != null
                        && amount.matches("[0-9]{1,17}\\.[0-9]{2}")
                        && new BigDecimal(amount).compareTo((BigDecimal) a.get("net_amount")) == 0);
        require(
                reference != null
                        && reference.equals(reference.trim())
                        && reference.matches("[A-Za-z0-9._/-]{1,120}"));
        require(
                transferred != null
                        && !transferred.isAfter(now().plusSeconds(60))
                        && a.get("qr_issued_at") != null
                        && !transferred.isBefore(time(a, "qr_issued_at")));
        require(evidence != null && !evidence.isEmpty());
        boundEvidence(id(p, "id"), id(a, "id"), evidence, actor, resolution);
        // The DB reserves the real bank reference. Its scope differs from the internal movement
        // identity.
        repository.attachEvidence(id(a, "id"), false, evidence);
        repository.updateAttempt(
                id(a, "id"),
                fields(
                        "source_account_id",
                        source,
                        "bank_transfer_date",
                        transferred.atZoneSameInstant(settings.getBankBusinessZone()).toLocalDate(),
                        "bank_transfer_reference",
                        reference,
                        "transferred_at",
                        transferred,
                        "confirmed_by",
                        actor.id(),
                        "confirmed_at",
                        now()));
        UUID tx = repository.movement(id(p, "id"), id(a, "id"), "PAYOUT", null);
        repository.updateAttempt(id(a, "id"), fields("state", "SUCCEEDED", "transaction_id", tx));
        receipts.updateReceipt(id(a, "id"), "AWAITING_CONFIRMATION", null);
        changePayout(
                p,
                fields(
                        "status",
                        "SUCCEEDED",
                        "transaction_id",
                        tx,
                        "confirmation_source",
                        "MANUAL_STAFF_CONFIRMED",
                        "succeeded_at",
                        now(),
                        "blocked_reason",
                        null,
                        "updated_by",
                        actor.id()));
        audit(
                p,
                id(a, "id"),
                null,
                "MANUAL_CONFIRMED",
                actor,
                resolution ? "Confirmed sent after reconciliation" : null,
                false);
        publish(p, a, "PayoutSucceeded", tx);
    }

    private void boundEvidence(
            UUID payout,
            UUID attempt,
            List<UUID> evidence,
            CurrentUser actor,
            boolean reconciliation) {
        if (evidence == null) return;
        if (evidence.size() > 3 || new HashSet<>(evidence).size() != evidence.size())
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        for (UUID evidenceId : evidence) {
            var row =
                    repository
                            .evidence(payout, attempt, evidenceId)
                            .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
            if (!reconciliation && !actor.id().equals(row.get("uploader_id")))
                throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
        }
    }

    private void evidenceAccess(CurrentUser actor, Map<String, Object> p, Map<String, Object> a) {
        if (actor.id().equals(a.get("claimed_by"))) return;
        access.admin(actor);
        require(
                disputes.cases(id(p, "id")).stream()
                        .anyMatch(c -> a.get("id").equals(c.get("attempt_id"))));
    }

    private boolean openCase(UUID payoutId) {
        return disputes.cases(payoutId).stream().anyMatch(c -> !"RESOLVED".equals(c.get("status")));
    }

    private void closeCase(Map<String, Object> review, CurrentUser actor, String resolution) {
        disputes.updateCase(
                id(review, "id"),
                fields(
                        "status",
                        "RESOLVED",
                        "resolution",
                        resolution,
                        "reviewed_by",
                        actor.id(),
                        "resolved_at",
                        now()));
    }

    private void sellerVersion(Map<String, Object> p, Map<String, Object> a, Long expected) {
        require(
                a != null
                        && "SUCCEEDED".equals(p.get("status"))
                        && "SUCCEEDED".equals(a.get("state")));
        require(
                expected != null
                        && expected >= 0
                        && number(a, "receipt_version").longValue() == expected);
    }

    private void changePayout(Map<String, Object> p, Map<String, Object> changes) {
        repository.updatePayout(id(p, "id"), changes);
    }

    private void audit(
            Map<String, Object> p,
            UUID attempt,
            UUID review,
            String event,
            CurrentUser actor,
            String reason,
            boolean seller) {
        String from =
                Set.of(
                                        "MANUAL_RESOLVED",
                                        "RECEIPT_CONFIRMED",
                                        "RECEIPT_DISPUTED",
                                        "DISPUTE_REVIEWED",
                                        "EVIDENCE_UPLOADED")
                                .contains(event)
                        ? str(repository.payout(id(p, "id"), false).orElseThrow(), "status")
                        : str(p, "status");
        repository.audit(
                id(p, "id"),
                attempt,
                review,
                event,
                from,
                actor.id(),
                SYSTEM.equals(actor.id())
                        ? "SYSTEM"
                        : seller ? "SELLER" : actor.hasRole("ADMIN") ? "ADMIN" : "SUPPORT_STAFF",
                reason == null ? null : reason.trim());
    }

    private byte[] encode(Map<String, Object> a) {
        try {
            var snapshot = ManualTransferSnapshot.fromAttempt(a);
            return qr.encode(
                    snapshot.bankCode(),
                    cipher.decrypt(snapshot.encryptedAccount()),
                    snapshot.netAmount(),
                    snapshot.reference());
        } catch (IllegalStateException e) {
            throw new DomainException(MANUAL_PAYOUT_UNAVAILABLE);
        }
    }

    private void publish(
            Map<String, Object> p, Map<String, Object> a, String event, UUID transaction) {
        var data =
                fields(
                        "payoutId",
                        p.get("id"),
                        "orderId",
                        p.get("order_id"),
                        "sellerId",
                        p.get("seller_id"),
                        "netAmount",
                        p.get("net_amount").toString(),
                        "currency",
                        p.get("currency"),
                        "attempt",
                        a.get("attempt_no"),
                        "manualAttemptId",
                        a.get("id"),
                        "transactionId",
                        transaction,
                        "simulated",
                        false,
                        "confirmationSource",
                        "MANUAL_STAFF_CONFIRMED");
        var span = tracer.nextSpan().name("manual-payout-transition").start();
        try (var scope = tracer.withSpan(span)) {
            outbox.write(
                    "Payout",
                    Topics.PAYMENT,
                    envelopes.create(event, p.get("id").toString(), data));
        } finally {
            span.end();
        }
    }

    private void intake() {
        require(settings.isEnabled());
    }

    private static void version(Map<String, Object> row, Long expected) {
        if (row == null || expected == null || expected < 0)
            throw new DomainException(MANUAL_PAYOUT_CONFLICT);
        if (number(row, "version").longValue() != expected)
            throw new DomainException(
                    MANUAL_PAYOUT_CONFLICT,
                    MANUAL_PAYOUT_CONFLICT.message(),
                    Map.of("currentVersion", number(row, "version").longValue()));
    }

    private static void require(boolean condition) {
        if (!condition) throw new DomainException(MANUAL_PAYOUT_CONFLICT);
    }

    private static void text(String text) {
        if (text == null || text.trim().isEmpty() || text.trim().length() > 1000)
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
    }

    private static UUID requestKey(String key) {
        try {
            if (key == null
                    || !key.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException();
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
