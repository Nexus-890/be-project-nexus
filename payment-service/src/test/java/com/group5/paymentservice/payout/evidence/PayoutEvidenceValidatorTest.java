package com.group5.paymentservice.payout.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.group5.common_core.exception.DomainException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

class PayoutEvidenceValidatorTest {
    private final PayoutEvidenceValidator validator = new PayoutEvidenceValidator();

    @Test
    void validatesPngJpegAndPdfByContentAndComputesReplayDigest() throws Exception {
        for (String format : new String[] {"png", "jpeg"}) {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, out);
            byte[] bytes = out.toByteArray();
            var result =
                    validator.validate(
                            new MockMultipartFile(
                                    "file", "misleading.exe", "image/" + format, bytes));
            assertThat(result.contentType()).isEqualTo("image/" + format);
            assertThat(result.size()).isEqualTo(bytes.length);
            assertThat(result.sha256())
                    .hasSize(64)
                    .isEqualTo(validator.validate(bytes, null).sha256());
        }
        var pdf =
                validator.validate(
                        "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF"
                                .getBytes(StandardCharsets.US_ASCII),
                        "application/pdf");
        assertThat(pdf.extension()).isEqualTo("pdf");
        assertThat(validator.validate(pdf.content(), "application/octet-stream").contentType())
                .isEqualTo("application/pdf");
        assertThat(
                        validator
                                .validate(pdf.content(), "application/pdf; charset=binary")
                                .contentType())
                .isEqualTo("application/pdf");
    }

    @Test
    void rejectsSpoofedExtensionAndMime() {
        assertStatus(
                () ->
                        validator.validate(
                                new MockMultipartFile(
                                        "file",
                                        "receipt.png",
                                        "image/png",
                                        "<html>not a receipt</html>"
                                                .getBytes(StandardCharsets.US_ASCII))),
                415);
        assertStatus(
                () ->
                        validator.validate(
                                "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII), "image/png"),
                415);
        assertStatus(
                () ->
                        validator.validate(
                                new MockMultipartFile(
                                        "file", "empty.pdf", "application/pdf", new byte[0])),
                400);
    }

    @Test
    void enforcesExactTenMiBBoundary() {
        byte[] bytes = new byte[PayoutEvidenceValidator.MAX_BYTES];
        Arrays.fill(bytes, (byte) ' ');
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 9);
        assertThat(validator.validate(bytes, "application/pdf").size())
                .isEqualTo(PayoutEvidenceValidator.MAX_BYTES);
        assertStatus(
                () -> validator.validate(Arrays.copyOf(bytes, bytes.length + 1), "application/pdf"),
                413);
    }

    private void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        error -> assertThat(error.getCode()).isEqualTo(status));
    }
}
