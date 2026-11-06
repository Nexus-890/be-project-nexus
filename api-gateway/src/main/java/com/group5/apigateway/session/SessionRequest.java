package com.group5.apigateway.session;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// browser gửi lên sau khi Keycloak redirect về: mã đăng nhập + PKCE verifier
public record SessionRequest(String code, String codeVerifier, String redirectUri) {

    // verifier PKCE dài 43-128 ký tự (RFC 7636), các giới hạn còn lại chỉ để chặn input quá khổ
    private static final int MAX_CODE = 2048;
    private static final int MAX_URI = 2048;

    public void requireValid() {
        require(code, 1, MAX_CODE, "code");
        require(codeVerifier, 43, 128, "codeVerifier");
        require(redirectUri, 1, MAX_URI, "redirectUri");
    }

    private static void require(String value, int min, int max, String field) {
        if (value == null || value.length() < min || value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is missing or has a wrong length");
        }
    }
}
