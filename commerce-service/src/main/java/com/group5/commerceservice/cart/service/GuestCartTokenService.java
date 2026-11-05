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

@Service
@RequiredArgsConstructor
public class GuestCartTokenService {
    private final CartProperties properties;

    //CartService sẽ lưu toàn bộ token vào carts.guest_token và trả token cho FE.
    //mục đích là tạo GuestToken cho guestCart
    //cấu trúc nó như dị nè : v1.<UUID ngẫu nhiên>.<chữ ký> - v1.550e8400-e29b-41d4-a716-446655440000.<chữ ký>
    public String create() {
        String owner = "v1." + UUID.randomUUID();
        return owner + "." + sign(owner);
    }

    public void validate(String token) {
        if (token == null || !token.matches("v1\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}")) {
            throw new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND);
        }
        int separator = token.lastIndexOf('.');//lấy ra được số 1
        byte[] expected = sign(token.substring(0, separator)).getBytes(StandardCharsets.US_ASCII);//chữ ký BE tính lại -> CHU_KY_A
        byte[] actual = token.substring(separator + 1).getBytes(StandardCharsets.US_ASCII);//chữ ký được gửi xún -> CHU_KY_A
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new DomainException(CommerceErrorCode.GUEST_CART_NOT_FOUND);
        }
    }
    //phần chữ ký
    //chữ ký = HMAC-SHA256(secretKey, owner)
    private String sign(String owner) {
        byte[] key;
        try {
            //giải mã cái cấu hình secretKey
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
