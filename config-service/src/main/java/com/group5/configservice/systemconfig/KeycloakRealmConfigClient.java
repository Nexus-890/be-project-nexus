package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.Map;

/** Đọc snapshot realm hiện tại bằng service account chỉ có quyền view-realm. */
@Component
public class KeycloakRealmConfigClient {
    private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() { };
    private final RestClient client;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    /** Không yêu cầu IAM sẵn sàng khi Config Server khởi động; chỉ GET admin mới gọi remote. */
    public KeycloakRealmConfigClient(
            @Value("${nexus.system-config.keycloak.server-url:http://localhost:18080}") String serverUrl,
            @Value("${nexus.system-config.keycloak.realm:nexus}") String realm,
            @Value("${nexus.system-config.keycloak.client-id:config-service}") String clientId,
            @Value("${nexus.system-config.keycloak.client-secret:}") String clientSecret,
            @Value("${nexus.system-config.keycloak.timeout:5s}") Duration timeout) {
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(serverUrl).requestFactory(requests).build();
    }

    /** Một lần đọc cho cả bốn key; không thay giá trị thực bằng default hoặc cache cũ khi IAM lỗi. */
    public Map<String, Object> currentValues() {
        if (clientSecret.isBlank()) throw unavailable();
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            Map<String, Object> token = client.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(OBJECT);
            if (token == null || !(token.get("access_token") instanceof String bearer) || bearer.isBlank())
                throw unavailable();
            Map<String, Object> settings = client.get().uri("/admin/realms/{realm}", realm)
                    .headers(headers -> headers.setBearerAuth(bearer)).retrieve().body(OBJECT);
            if (settings == null || !(settings.get("bruteForceProtected") instanceof Boolean protectedRealm)
                    || !(settings.get("passwordPolicy") instanceof String policy)) throw unavailable();
            return Map.of(
                    "SESSION_TIMEOUT_MINUTES", minutes(settings, "ssoSessionIdleTimeout"),
                    "MAX_LOGIN_ATTEMPTS", protectedRealm ? nonnegativeInteger(settings, "failureFactor") : 0,
                    "PASSWORD_POLICY", policy,
                    "ACCESS_TOKEN_EXPIRY_MINUTES", minutes(settings, "accessTokenLifespan"));
        } catch (RestClientException | IllegalArgumentException | ArithmeticException exception) {
            // Không ghi token, secret hay response body vào log/Problem Details.
            throw unavailable();
        }
    }

    /** Giữ phần phút lẻ khi realm cấu hình theo giây, thay vì chia nguyên làm sai giá trị. */
    private static BigDecimal minutes(Map<String, Object> settings, String field) {
        return BigDecimal.valueOf(nonnegativeInteger(settings, field))
                .divide(BigDecimal.valueOf(60), MathContext.DECIMAL64).stripTrailingZeros();
    }

    /** Payload thiếu trường hoặc sai kiểu phải báo unavailable thay vì chế tạo cấu hình hiệu lực. */
    private static int nonnegativeInteger(Map<String, Object> settings, String field) {
        if (!(settings.get(field) instanceof Number number)) throw unavailable();
        int value = new BigDecimal(number.toString()).intValueExact();
        if (value < 0) throw unavailable();
        return value;
    }

    private static DomainException unavailable() {
        return new DomainException(ConfigErrorCode.CONFIG_IDENTITY_PROVIDER_UNAVAILABLE);
    }
}
