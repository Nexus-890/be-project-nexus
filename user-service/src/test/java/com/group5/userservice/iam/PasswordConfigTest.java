package com.group5.userservice.iam;

import com.group5.userservice.shared.config.PasswordConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordConfigTest {

    @Test
    void passwordEncoderUsesBcryptCost12() {
        String hash = new PasswordConfig().passwordEncoder().encode("correct-password");

        assertThat(hash).startsWith("$2a$12$");
    }
}
