package com.group5.paymentservice.payout.evidence;

import com.group5.common_core.exception.DomainException;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Component
public class PayoutEvidenceValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILES_PER_ATTEMPT = 3;
    private static final Map<String, String> EXTENSIONS =
            Map.of("image/jpeg", "jpg", "image/png", "png", "application/pdf", "pdf");
    private final Tika tika = new Tika();

    public ValidatedEvidence validate(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        if (file.getSize() > MAX_BYTES)
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_TOO_LARGE);
        try (var input = file.getInputStream()) {
            return validate(input.readNBytes(MAX_BYTES + 1), file.getContentType());
        } catch (IOException e) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        }
    }

    public ValidatedEvidence validate(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0)
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        if (bytes.length > MAX_BYTES)
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_TOO_LARGE);
        // Detect from bytes alone; neither file extension nor supplied MIME influences detection.
        String detected = tika.detect(bytes);
        String extension = EXTENSIONS.get(detected);
        String declared =
                declaredContentType == null ? "" : declaredContentType.split(";", 2)[0].trim();
        if (extension == null
                || !declared.isBlank()
                        && !"application/octet-stream".equalsIgnoreCase(declared)
                        && !declared.equalsIgnoreCase(detected)) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNSUPPORTED);
        }
        try {
            String digest =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ValidatedEvidence(bytes, detected, extension, digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    public record ValidatedEvidence(
            byte[] content, String contentType, String extension, String sha256) {
        public int size() {
            return content.length;
        }
    }
}
