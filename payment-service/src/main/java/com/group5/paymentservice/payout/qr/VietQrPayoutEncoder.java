package com.group5.paymentservice.payout.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

/** NAPAS 247 account-transfer profile (QRIBFTTA), rendered entirely in this process. */
@Component
public class VietQrPayoutEncoder implements PayoutQrEncoder {
    // Snapshot of transferSupported bank BINs from https://api.vietqr.io/v2/banks.
    // E-wallet BINs (971xxx) are outside this bank-account payout release.
    private static final Set<String> TRANSFER_BANK_BINS =
            Set.of(
                    "422589", "546034", "546035", "668888", "963388", "970400", "970403", "970405",
                    "970407", "970409", "970412", "970414", "970415", "970416", "970418", "970419",
                    "970422", "970423", "970424", "970425", "970426", "970427", "970428", "970429",
                    "970430", "970431", "970432", "970433", "970434", "970436", "970437", "970438",
                    "970440", "970441", "970443", "970444", "970446", "970448", "970449", "970452",
                    "970454", "970457", "970462", "970463");
    private static final BigDecimal PROTOCOL_MAX_AMOUNT = new BigDecimal("499999999");

    public VietQrPayoutEncoder(ManualPayoutProperties properties) {
        // Configurable rollout limits are checked by the service at claim/issuance.
        // Re-rendering an issued snapshot must survive a later decrease of that limit.
    }

    @Override
    public byte[] encode(
            String bankBin, String accountNumber, BigDecimal amount, String reference) {
        String payload = payload(bankBin, accountNumber, amount, reference);
        try {
            var matrix =
                    new QRCodeWriter()
                            .encode(
                                    payload,
                                    BarcodeFormat.QR_CODE,
                                    512,
                                    512,
                                    Map.of(
                                            EncodeHintType.ERROR_CORRECTION,
                                            ErrorCorrectionLevel.M,
                                            EncodeHintType.MARGIN,
                                            4));
            var image =
                    new BufferedImage(
                            matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
                }
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output))
                throw new IOException("PNG encoder unavailable");
            return output.toByteArray();
        } catch (WriterException | IOException e) {
            // Provider/account/payload data must never be included in error detail or logs.
            throw new DomainException(ManualPayoutQrErrorCode.QR_UNAVAILABLE);
        }
    }

    public String payload(
            String bankBin, String accountNumber, BigDecimal amount, String reference) {
        if (!supportsBank(bankBin)
                || accountNumber == null
                || !accountNumber.matches("[0-9]{4,25}")
                || amount == null
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 0
                || amount.compareTo(PROTOCOL_MAX_AMOUNT) > 0
                || reference == null
                || !reference.matches("[A-Za-z0-9 ]{1,25}")
                || !reference.equals(reference.trim())
                || reference.isBlank()) {
            throw new DomainException(ManualPayoutQrErrorCode.INVALID_QR_DATA);
        }
        String recipient = tlv("00", bankBin) + tlv("01", accountNumber);
        String merchant = tlv("00", "A000000727") + tlv("01", recipient) + tlv("02", "QRIBFTTA");
        String encoded =
                tlv("00", "01")
                        + tlv("01", "12")
                        + tlv("38", merchant)
                        + tlv("53", "704")
                        + tlv("54", amount.toBigIntegerExact().toString())
                        + tlv("58", "VN")
                        + tlv("62", tlv("08", reference))
                        + "6304";
        return encoded + checksum(encoded);
    }

    public static boolean supportsBank(String bankBin) {
        return bankBin != null && TRANSFER_BANK_BINS.contains(bankBin);
    }

    private static String tlv(String tag, String value) {
        int length = value.getBytes(StandardCharsets.US_ASCII).length;
        if (length > 99) throw new DomainException(ManualPayoutQrErrorCode.INVALID_QR_DATA);
        return tag + String.format(Locale.ROOT, "%02d", length) + value;
    }

    private static String checksum(String content) {
        int crc = 0xffff;
        for (byte value : content.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (value & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc << 1) ^ ((crc & 0x8000) == 0 ? 0 : 0x1021)) & 0xffff;
            }
        }
        return String.format(Locale.ROOT, "%04X", crc);
    }
}
