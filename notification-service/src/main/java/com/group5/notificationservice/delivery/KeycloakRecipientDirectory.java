package com.group5.notificationservice.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.group5.notificationservice.shared.config.KeycloakProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Component
@ConditionalOnProperty(prefix = "nexus.notification", name = "email-enabled", havingValue = "true")
public class KeycloakRecipientDirectory implements RecipientDirectory {

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

    private final KeycloakProperties properties;
    private final Clock clock;
    private final RestClient client;
    private String accessToken;
    private Instant accessTokenRefreshAt = Instant.MIN;

    public KeycloakRecipientDirectory(KeycloakProperties properties, Clock clock,
                                      ObjectProvider<ObservationRegistry> observations) {
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException(
                    "Set KEYCLOAK_NOTIFICATION_SERVICE_CLIENT_SECRET when EMAIL_NOTIFICATION_ENABLED=true");
        }
        this.properties = properties;
        this.clock = clock;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeout());
        requestFactory.setReadTimeout(properties.getTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.getServerUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    // KEYCLOAK GIỮ EMAIL MỚI NHẤT (SAU #238). KHÔNG CÓ USER / KHÔNG CÓ EMAIL -> LỖI VĨNH VIỄN
    // KEYCLOAK SẬP / TIMEOUT -> LỖI TẠM THỜI, JOB SẼ GỬI LẠI SAU
    @Override
    public String emailOf(UUID userId) {
        try {
            KeycloakUser user = withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(KeycloakUser.class));
            if (user == null || user.email() == null || user.email().isBlank()) {
                throw new DeliveryFailure("RECIPIENT_NO_EMAIL", false);
            }
            return user.email();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new DeliveryFailure("RECIPIENT_NOT_FOUND", false, exception);
        } catch (RestClientException exception) {
            throw new DeliveryFailure("RECIPIENT_LOOKUP_UNAVAILABLE", true, exception);
        }
    }

    private <T> T withToken(Function<String, T> request) {
        String token = accessToken();
        try {
            return request.apply(token);
        } catch (HttpClientErrorException.Unauthorized exception) {
            discard(token);
            return request.apply(accessToken());
        }
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenRefreshAt)) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        TokenResponse token = client.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (token == null || token.accessToken() == null) {
            throw new DeliveryFailure("RECIPIENT_LOOKUP_UNAVAILABLE", true);
        }
        accessToken = token.accessToken();
        accessTokenRefreshAt = now.plusSeconds(token.expiresIn()).minus(TOKEN_REFRESH_MARGIN);
        return accessToken;
    }

    private synchronized void discard(String token) {
        if (token.equals(accessToken)) {
            accessToken = null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KeycloakUser(String email) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) { }
}
