package com.group5.paymentservice.sellerbank.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Component
public class BankAccountCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] AAD = "nexus:seller-bank:v1".getBytes(StandardCharsets.US_ASCII);
    private final String configuredKey;

    public BankAccountCipher(
            @Value("${nexus.payout.account-encryption-key:${PAYOUT_ACCOUNT_ENCRYPTION_KEY:}}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    // Mỗi lần mã hóa có nonce riêng; lưu dạng v1:base64(nonce || ciphertext || tag) để đọc lại đúng phiên bản.
    public String encrypt(String accountNumber) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(accountNumber.getBytes(StandardCharsets.US_ASCII));
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return "v1:" + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Bank-account encryption is unavailable");
        }
    }

    // GCM xác thực ciphertext trước khi trả số tài khoản, dữ liệu bị sửa sẽ bị từ chối.
    public String decrypt(String envelope) {
        try {
            if (envelope == null || !envelope.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(envelope.substring(3));
            if (bytes.length < 29) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Bank-account ciphertext could not be authenticated");
        }
    }

    private SecretKeySpec key() {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("PAYOUT_ACCOUNT_ENCRYPTION_KEY must be a base64 AES-256 key");
        }
        if (key.length != 32) throw new IllegalStateException("PAYOUT_ACCOUNT_ENCRYPTION_KEY must be a base64 AES-256 key");
        return new SecretKeySpec(key, "AES");
    }
}
