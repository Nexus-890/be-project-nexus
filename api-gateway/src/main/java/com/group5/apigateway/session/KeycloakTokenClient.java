package com.group5.apigateway.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

// gọi token và logout của Keycloak thay cho trình duyệt (nexus-web là public client nên không có secret)
// địa chỉ realm lấy từ issuer-uri mà bộ lọc JWT đang dùng, khỏi phải giữ thêm một URL thứ hai
// WebClient tự tạo ở đây, không lấy builder @LoadBalanced trong WebclientConfig vì Keycloak không nằm trong Eureka
@Component
public class KeycloakTokenClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakTokenClient.class);

    private final WebClient client;
    private final String clientId;
    private final Duration timeout;

    public KeycloakTokenClient(OAuth2ResourceServerProperties resourceServer, SessionProperties properties) {
        String issuer = resourceServer.getJwt().getIssuerUri();
        Assert.hasText(issuer, "JWT issuer-uri is required to reach Keycloak");
        this.client = WebClient.create(issuer.replaceAll("/+$", "") + "/protocol/openid-connect");
        this.clientId = properties.clientId();
        this.timeout = properties.keycloakTimeout();
    }

    // đổi mã dùng một lần + PKCE verifier lấy token
    public Mono<KeycloakTokens> exchangeCode(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = form("authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        return requestTokens(form);
    }

    // đổi refresh token trong cookie lấy token mới
    // Keycloak xoay refresh token: cái trả về thay cái cũ, đưa cái cũ lần nữa thì Keycloak hủy cả phiên
    public Mono<KeycloakTokens> refresh(String refreshToken) {
        MultiValueMap<String, String> form = form("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestTokens(form);
    }

    // kết thúc phiên Keycloak. Bị từ chối nghĩa là phiên đã mất rồi, đúng ý mình nên bỏ qua lỗi
    public Mono<Void> logout(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("refresh_token", refreshToken);
        return client.post().uri("/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .then()
                .onErrorResume(error -> {
                    log.warn("KEYCLOAK_LOGOUT_FAILED reason={}", error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    private Mono<KeycloakTokens> requestTokens(MultiValueMap<String, String> form) {
        return client.post().uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                // mã hoặc refresh token đã dùng, hết hạn hay giả thì Keycloak trả 400 invalid_grant
                // với trình duyệt thì đều là "đăng nhập lại" nên gom 400 và 401 thành 401
                .onStatus(status -> status.value() == 400 || status.value() == 401,
                        response -> Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                                "Keycloak refused the code or the refresh token")))
                .onStatus(status -> status.isError(),
                        response -> Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Keycloak could not answer the token request")))
                .bodyToMono(KeycloakTokens.class)
                .timeout(timeout)
                .onErrorMap(TimeoutException.class,
                        error -> new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Keycloak did not answer in time"));
    }

    private MultiValueMap<String, String> form(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", clientId);
        return form;
    }
}
