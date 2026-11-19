package com.group5.paymentservice.payment.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payment.dto.response.RefundEvidenceView;
import com.group5.paymentservice.payment.dto.response.RefundView;
import com.group5.paymentservice.payment.entity.Refund;
import com.group5.paymentservice.payment.enums.RefundStatus;
import com.group5.paymentservice.payment.repository.RefundRepository;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceErrorCode;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceStorage;
import com.group5.paymentservice.payout.evidence.PayoutEvidenceValidator;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RefundEvidenceService {
    static final int MAX_FILES = 3;
    private static final String PREFIX = "refunds/";

    private final RefundRepository refunds;
    private final JdbcTemplate jdbc;
    private final PayoutEvidenceStorage storage;
    private final PayoutEvidenceValidator validator;
    private final ManualPayoutProperties settings;
    private final TransactionTemplate transaction;

    public RefundEvidenceService(
            RefundRepository refunds,
            JdbcTemplate jdbc,
            PayoutEvidenceStorage storage,
            PayoutEvidenceValidator validator,
            ManualPayoutProperties settings,
            PlatformTransactionManager transactionManager) {
        this.refunds = refunds;
        this.jdbc = jdbc;
        this.storage = storage;
        this.validator = validator;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setTimeout(10);
    }

    public RefundView upload(CurrentUser user, UUID refundId, String bankReference, MultipartFile file) {
        if (user == null || user.id() == null) {
            throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        }
        String reference = bankReference == null ? "" : bankReference.strip();
        if (reference.isEmpty() || reference.length() > 100) {
            throw new DomainException(PaymentErrorCode.REFUND_EVIDENCE_INVALID);
        }
        var validated = validator.validate(file);
        if (Boolean.TRUE.equals(transaction.execute(status -> stored(refundId, validated.sha256(), false)))) {
            return view(refundId);
        }
        if (!storage.configured()) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        }
        UUID evidenceId = UUID.randomUUID();
        String objectKey = PREFIX + refundId + "/" + evidenceId + "." + validated.extension();
        boolean kept = false;
        try {
            storage.put(objectKey, validated.content(), validated.contentType());
            kept = Boolean.TRUE.equals(transaction.execute(status -> {
                if (stored(refundId, validated.sha256(), true)) {
                    return false;
                }
                jdbc.update("""
                        INSERT INTO refund_evidence (id, refund_id, uploader_id, bucket, object_key, content_type,
                                                     size_bytes, digest, bank_reference)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, evidenceId, refundId, user.id(), settings.getEvidence().getBucket(), objectKey,
                        validated.contentType(), validated.size(), validated.sha256(), reference);
                return true;
            }));
        } finally {
            if (!kept) {
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return view(refundId);
    }

    @Transactional(readOnly = true)
    public PayoutEvidenceStorage.StoredEvidence evidence(UUID refundId, UUID evidenceId) {
        String objectKey = jdbc.queryForList(
                        "SELECT object_key FROM refund_evidence WHERE id = ? AND refund_id = ?",
                        String.class, evidenceId, refundId)
                .stream().findFirst()
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
        return storage.get(objectKey);
    }

    @Transactional(readOnly = true)
    public List<RefundView> withEvidence(List<RefundView> views) {
        if (views.isEmpty()) {
            return views;
        }
        Map<UUID, List<RefundEvidenceView>> files = jdbc.query("""
                        SELECT id, refund_id, content_type, size_bytes, bank_reference, uploader_id, uploaded_at
                        FROM refund_evidence WHERE refund_id = ANY (?) ORDER BY uploaded_at, id
                        """,
                        (row, number) -> Map.entry(row.getObject("refund_id", UUID.class), new RefundEvidenceView(
                                row.getObject("id", UUID.class), row.getString("content_type"),
                                row.getInt("size_bytes"), row.getString("bank_reference"),
                                row.getObject("uploader_id", UUID.class),
                                row.getObject("uploaded_at", OffsetDateTime.class))),
                        (Object) views.stream().map(RefundView::refundId).toArray(UUID[]::new))
                .stream().collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return views.stream()
                .map(view -> view.withEvidence(files.getOrDefault(view.refundId(), List.of())))
                .toList();
    }

    public RefundView withEvidence(RefundView view) {
        return withEvidence(List.of(view)).getFirst();
    }

    private RefundView view(UUID refundId) {
        return withEvidence(refunds.findById(refundId).map(RefundService::view)
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND)));
    }

    private boolean stored(UUID refundId, String digest, boolean lock) {
        Refund refund = (lock ? refunds.findLockedById(refundId) : refunds.findById(refundId))
                .orElseThrow(() -> new DomainException(PaymentErrorCode.REFUND_NOT_FOUND));
        Map<String, Long> counts = jdbc.queryForList(
                        "SELECT digest FROM refund_evidence WHERE refund_id = ?", String.class, refundId)
                .stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (counts.containsKey(digest)) {
            return true;
        }
        if (refund.getStatus() == RefundStatus.COMPLETED) {
            throw new DomainException(PaymentErrorCode.REFUND_COMPLETED);
        }
        if (counts.size() >= MAX_FILES) {
            throw new DomainException(PaymentErrorCode.REFUND_EVIDENCE_LIMIT);
        }
        return false;
    }
}
