package com.group5.apigateway.session;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.Optional;

// tạo, đọc, xóa cookie refresh token. Mọi cờ của cookie nằm một chỗ này
@Component
public class SessionCookies {

    private final SessionProperties properties;

    public SessionCookies(SessionProperties properties) {
        this.properties = properties;
    }

    // HttpOnly để JS không đọc được giá trị, đó là mục đích của cả issue
    public ResponseCookie issue(String refreshToken, long lifetimeSeconds) {
        return base(refreshToken).maxAge(Duration.ofSeconds(Math.max(lifetimeSeconds, 1))).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(ServerWebExchange exchange) {
        var cookie = exchange.getRequest().getCookies().getFirst(properties.cookieName());
        return cookie == null || cookie.getValue().isBlank() ? Optional.empty() : Optional.of(cookie.getValue());
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.sameSite())
                .path(properties.cookiePath());
    }
}
