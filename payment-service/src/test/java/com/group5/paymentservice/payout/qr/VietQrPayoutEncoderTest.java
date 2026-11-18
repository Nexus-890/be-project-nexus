package com.group5.paymentservice.payout.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.imageio.ImageIO;

class VietQrPayoutEncoderTest {
    private final VietQrPayoutEncoder encoder =
            new VietQrPayoutEncoder(new ManualPayoutProperties());

    @Test
    void matchesAnIndependentPublishedVietQrOriginVector() {
        // MIT-licensed upstream fixture decoded from a VietQR service image:
        // https://github.com/vietqr/vietqr-node/blob/main/test/index.js (ORIGIN, synthetic ACB
        // example).
        String expected =
                "00020101021238530010A0000007270123000697041601091234567890208QRIBFTTA"
                        + "530370454061500005802VN62140810thanh toan6304565D";
        assertThat(
                        encoder.payload(
                                "970416", "123456789", new BigDecimal("150000.00"), "thanh toan"))
                .isEqualTo(expected);
    }

    @Test
    void independentImageDecodeAndTlvParsingPreserveExactFrozenData() throws Exception {
        byte[] png =
                encoder.encode("970436", "0011002299881", new BigDecimal("95000.00"), "NXP272A17");
        assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels =
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        var bitmap =
                new BinaryBitmap(
                        new HybridBinarizer(
                                new RGBLuminanceSource(
                                        image.getWidth(), image.getHeight(), pixels)));
        String decoded = new MultiFormatReader().decode(bitmap).getText();
        var fields = tlv(decoded);
        var merchant = tlv(fields.get("38"));
        var recipient = tlv(merchant.get("01"));
        assertThat(fields)
                .containsEntry("00", "01")
                .containsEntry("01", "12")
                .containsEntry("53", "704")
                .containsEntry("54", "95000")
                .containsEntry("58", "VN");
        assertThat(merchant).containsEntry("00", "A000000727").containsEntry("02", "QRIBFTTA");
        assertThat(recipient).containsEntry("00", "970436").containsEntry("01", "0011002299881");
        assertThat(tlv(fields.get("62"))).containsEntry("08", "NXP272A17");
        // A second render is the same instruction, with no fresh reference/deadline.
        assertThat(encoder.encode("970436", "0011002299881", new BigDecimal("95000"), "NXP272A17"))
                .isEqualTo(png);
    }

    @Test
    void purePngDecodingPreservesVariedInstructionsIncludingFinderLikeDataPatterns()
            throws Exception {
        // Reproduces the synthetic instruction for which the camera-oriented detector selected
        // finder-like data patterns. Decode the clean PNG matrix without camera localization.
        var references = new java.util.ArrayList<String>();
        references.add("NXP2c3f3fb7f0ff4596a7bd");
        var random = new Random(272L);
        for (int index = 0; index < 64; index++)
            references.add(
                    "NXP"
                            + new UUID(random.nextLong(), random.nextLong())
                                    .toString()
                                    .replace("-", "")
                                    .substring(0, 20));
        for (String reference : references) {
            byte[] png =
                    encoder.encode("970418", "000009998877", new BigDecimal("224250"), reference);
            var image = ImageIO.read(new ByteArrayInputStream(png));
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
            String decoded =
                    new MultiFormatReader()
                            .decode(
                                    new BinaryBitmap(new HybridBinarizer(source)),
                                    Map.of(DecodeHintType.PURE_BARCODE, true))
                            .getText();
            assertThat(decoded)
                    .isEqualTo(
                            encoder.payload(
                                    "970418", "000009998877", new BigDecimal("224250"), reference));
            var fields = tlv(decoded);
            assertThat(fields).containsEntry("54", "224250").containsEntry("53", "704");
            assertThat(tlv(tlv(fields.get("38")).get("01")))
                    .containsEntry("00", "970418")
                    .containsEntry("01", "000009998877");
            assertThat(tlv(fields.get("62"))).containsEntry("08", reference);
        }
    }

    @Test
    void unsupportedInputIsRejectedWithoutRoundingOrExposingRecipient() {
        for (String amount :
                new String[] {"0", "-1", "1.01", "500000000", "999999999999999999999999"}) {
            assertThatThrownBy(
                            () ->
                                    encoder.encode(
                                            "970436",
                                            "0011002299881",
                                            new BigDecimal(amount),
                                            "NXP272"))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            error -> {
                                assertThat(error.getErrorCode())
                                        .isEqualTo("PAYMENT_MANUAL_PAYOUT_INVALID_QR_DATA");
                                assertThat(error.getMessage()).doesNotContain("0011002299881");
                            });
        }
        assertThatThrownBy(() -> encoder.encode("VCB", "0011002299881", BigDecimal.ONE, "NXP272"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () -> encoder.encode("999999", "0011002299881", BigDecimal.ONE, "NXP272"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () -> encoder.encode("971025", "0011002299881", BigDecimal.ONE, "NXP272"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                encoder.encode(
                                        "970436",
                                        "12345678901234567890123456",
                                        BigDecimal.ONE,
                                        "NXP272"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () ->
                                encoder.encode(
                                        "970436", "0011002299881", BigDecimal.ONE, "BAD&REFERENCE"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rerenderOfAnIssuedSnapshotSurvivesADecreasedRolloutLimit() {
        var settings = new ManualPayoutProperties();
        settings.setMaxAmountVnd(new BigDecimal("100000"));
        var limited = new VietQrPayoutEncoder(settings);
        assertThat(limited.payload("970436", "0011002299881", new BigDecimal("100000"), "NXP272"))
                .contains("5406100000");
        assertThat(limited.encode("970436", "0011002299881", new BigDecimal("100001"), "NXP272"))
                .isNotEmpty();
    }

    private Map<String, String> tlv(String value) {
        var result = new LinkedHashMap<String, String>();
        for (int offset = 0; offset < value.length(); ) {
            String id = value.substring(offset, offset + 2);
            int length = Integer.parseInt(value.substring(offset + 2, offset + 4));
            assertThat(offset + 4 + length).isLessThanOrEqualTo(value.length());
            assertThat(result.put(id, value.substring(offset + 4, offset + 4 + length))).isNull();
            offset += 4 + length;
        }
        return result;
    }
}
