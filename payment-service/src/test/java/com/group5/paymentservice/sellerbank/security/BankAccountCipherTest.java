package com.group5.paymentservice.sellerbank.security;

import com.group5.paymentservice.sellerbank.dto.request.BankAccountRequest;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class BankAccountCipherTest {
    private final BankAccountCipher cipher = new BankAccountCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptionKeepsLeadingZeroesUsesFreshNoncesAndAuthenticatesTheCiphertext() {
        String first = cipher.encrypt("000012345678");
        String second = cipher.encrypt("000012345678");
        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("000012345678");
        assertThat(cipher.decrypt(first)).isEqualTo("000012345678");
        byte[] bytes = Base64.getDecoder().decode(first.substring(3));
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("000012345678");
    }

    @Test
    void missingOrWrongKeysNeverFallBackToPlaintext() {
        assertThatThrownBy(() -> new BankAccountCipher("").encrypt("000012345678"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PAYOUT_ACCOUNT_ENCRYPTION_KEY");
        byte[] differentKey = new byte[32];
        differentKey[0] = 1;
        String encrypted = cipher.encrypt("000012345678");
        assertThatThrownBy(() -> new BankAccountCipher(Base64.getEncoder().encodeToString(differentKey)).decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new BankAccountRequest("970415", "000012345678", "TEST SELLER", true, true).toString())
                .doesNotContain("000012345678");
    }
}
