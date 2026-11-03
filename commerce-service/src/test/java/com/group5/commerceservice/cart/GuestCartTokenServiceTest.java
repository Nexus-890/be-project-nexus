package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.service.GuestCartTokenService;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class GuestCartTokenServiceTest {
    @Test void signedTokensAreUniqueAndTamperedOrNonCanonicalSignaturesAreRejected() {
        CartProperties properties = new CartProperties();
        properties.setGuestTokenSecret(Base64.getEncoder().encodeToString(new byte[32]));
        var tokens = new GuestCartTokenService(properties);
        String token = tokens.create();
        tokens.validate(token);
        assertThat(tokens.create()).isNotEqualTo(token);
        for (String invalid : new String[]{null, "", "random", token + "=", token.substring(0, token.length() - 1),
                token.substring(0, 3) + "00000000-0000-0000-0000-000000000000" + token.substring(39),
                token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A")}) {
            assertThatThrownBy(() -> tokens.validate(invalid)).isInstanceOfSatisfying(DomainException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo("COMMERCE_GUEST_CART_NOT_FOUND"));
        }
    }

    @Test void missingWeakOrMalformedSigningKeysFailClosed() {
        CartProperties properties = new CartProperties();
        var tokens = new GuestCartTokenService(properties);
        for (String secret : new String[]{null, "", "not base64", Base64.getEncoder().encodeToString(new byte[31])}) {
            properties.setGuestTokenSecret(secret);
            assertThatThrownBy(tokens::create).isInstanceOfSatisfying(DomainException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo("COMMERCE_INVALID_GUEST_CONFIGURATION"));
        }
    }
}
