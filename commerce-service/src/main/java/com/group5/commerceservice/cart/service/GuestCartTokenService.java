package com.group5.commerceservice.cart.service;

import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/** Opaque owner capability. Cart state and inactivity determine its lifetime. */
@Service
@RequiredArgsConstructor
public class GuestCartTokenService {
    private final CartProperties properties;

    public String create() {
        String owner = "v1." + UUID.randomUUID();
        return owner + "." + sign(owner);
    }

    public void validate(String token) {
        if (token == null || !token.matches("v1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}")) {
            throw new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND);
        }
        int separator = token.lastIndexOf('.');
        byte[] expected = sign(token.substring(0, separator)).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = token.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND);
        }
    }

    private String sign(String owner) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(properties.getGuestTokenSecret());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new DomainException(CommerceErrorCode.INVALID_GUEST_CONFIGURATION);
        }
        if (key.length < 32) throw new DomainException(CommerceErrorCode.INVALID_GUEST_CONFIGURATION);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(owner.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }
}
