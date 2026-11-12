package com.group5.shipmentservice.shipping.adapter;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CarrierWebhookAuthentication {
    private CarrierWebhookAuthentication() {
    }

    public static void verify(String secret, String signature) {
        if (secret == null || secret.isBlank() || signature == null || signature.isBlank()
                || signature.length() > 4096 || !MessageDigest.isEqual(digest(secret), digest(signature))) {
            throw new DomainException(ShipmentErrorCode.INVALID_SIGNATURE);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
