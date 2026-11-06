package com.group5.apigateway.session;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// giữ refresh token của Keycloak ngoài tầm với của JavaScript
// 3 endpoint này khai public trong api-gateway.yml vì không có bearer token, mã đăng nhập hoặc cookie chính là credential
// controller thắng route user-service dù route đó cũng khớp /api/v1/auth/**, nên các path này không bao giờ xuống user-service
// giới hạn đã biết: chúng không đi qua rate limit theo IP (cái đó gắn vào route). Mã chỉ dùng được một lần,
// refresh token xoay mỗi lần, và Keycloak có chống brute-force riêng, đủ cho rủi ro thật ở đây
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {

    private final KeycloakTokenClient keycloak;
    private final SessionCookies cookies;
    private final SessionGuard guard;

    public SessionController(KeycloakTokenClient keycloak, SessionCookies cookies, SessionGuard guard) {
        this.keycloak = keycloak;
        this.cookies = cookies;
        this.guard = guard;
    }

    // sau khi đăng nhập: trình duyệt đưa mã, gateway trả access token và đặt cookie
    @PostMapping("/session")
    public Mono<ResponseEntity<ApiEnvelope<SessionResponse>>> createSession(
            @RequestBody SessionRequest request, ServerWebExchange exchange) {
        guard.verify(exchange);
        request.requireValid();
        return keycloak.exchangeCode(request.code(), request.codeVerifier(), request.redirectUri())
                .map(this::signedIn);
    }

    // lúc tải lại trang hoặc trước khi access token hết hạn: cookie đổi lấy token mới và cookie mới
    @PostMapping("/refresh")
    public Mono<ResponseEntity<ApiEnvelope<SessionResponse>>> refresh(ServerWebExchange exchange) {
        guard.verify(exchange);
        // khách chưa đăng nhập: không có gì để làm mới, trả 204 chứ không 401
        // vì trang nào cũng gọi lúc tải, 401 sẽ làm trình duyệt báo lỗi đỏ cho mọi khách
        String refreshToken = cookies.read(exchange).orElse(null);
        if (refreshToken == null) {
            return Mono.just(ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
        }
        return keycloak.refresh(refreshToken)
                .map(this::signedIn)
                // refresh token bị từ chối là hết dùng hẳn (hết hạn, bị thu hồi hoặc đã dùng rồi)
                // nên xóa cookie để trình duyệt quay về trang đăng nhập, không thử lại mãi
                .doOnError(ResponseStatusException.class, error -> {
                    if (error.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                        exchange.getResponse().addCookie(cookies.clear());
                    }
                });
    }

    // luôn xóa cookie, kể cả khi Keycloak không gọi được, để người dùng vẫn đăng xuất được ở phía mình
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        guard.verify(exchange);
        Mono<Void> ended = cookies.read(exchange).map(keycloak::logout).orElseGet(Mono::empty);
        return ended.thenReturn(ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build());
    }

    private ResponseEntity<ApiEnvelope<SessionResponse>> signedIn(KeycloakTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(tokens.refreshToken(), tokens.refreshExpiresIn()).toString())
                // body có bearer token nên trình duyệt hay proxy không được lưu cache
                .cacheControl(CacheControl.noStore())
                .body(ApiEnvelope.ok(new SessionResponse(tokens.accessToken(), tokens.idToken(), tokens.expiresIn())));
    }
}
