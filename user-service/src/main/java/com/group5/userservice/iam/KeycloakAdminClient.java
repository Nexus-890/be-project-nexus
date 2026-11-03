package com.group5.userservice.iam;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.config.KeycloakProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Calls the Keycloak Admin REST API as the user-service service account. */
@Component
public class KeycloakAdminClient {
    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

    private final KeycloakProperties properties;
    private final Clock clock;
    private final RestClient client;
    private String accessToken;
    private Instant accessTokenRefreshAt = Instant.MIN;

    public KeycloakAdminClient(KeycloakProperties properties, Clock clock,
                               ObjectProvider<ObservationRegistry> observations) {
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

    public Optional<RealmRole> realmRole(String name) {
        try {
            return Optional.ofNullable(withToken(token -> client.get()
                    .uri("/admin/realms/{realm}/roles/{role}", properties.getRealm(), name)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(RealmRole.class)));
        } catch (HttpClientErrorException.NotFound exception) {
            return Optional.empty();
        } catch (RestClientException exception) {
            throw unavailable("read realm role " + name, exception);
        }
    }

    public UUID createUser(String email, String password, String fullName) {
        int split = fullName.lastIndexOf(' ');
        String firstName = split < 0 ? fullName : fullName.substring(0, split).strip();
        String lastName = split < 0 ? fullName : fullName.substring(split + 1);
        NewUser user = new NewUser(email, email, firstName, lastName, true, false,
                List.of(new Credential("password", password, false)));
        try {
            URI location = withToken(token -> client.post()
                    .uri("/admin/realms/{realm}/users", properties.getRealm())
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation());
            if (location == null) {
                throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
            }
            String path = location.getPath();
            return UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        } catch (HttpClientErrorException.Conflict exception) {
            throw new DomainException(UserErrorCode.USER_EMAIL_ALREADY_USED);
        } catch (HttpClientErrorException.BadRequest exception) {
            log.warn("Keycloak rejected a new account: {}", exception.getResponseBodyAsString());
            throw new DomainException(UserErrorCode.USER_IDENTITY_REJECTED);
        } catch (RestClientException exception) {
            throw unavailable("create user", exception);
        }
    }

    public void assignRealmRole(UUID userId, RealmRole role) {
        try {
            withToken(token -> client.post()
                    .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm", properties.getRealm(), userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientException exception) {
            throw unavailable("assign realm role " + role.name(), exception);
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
        TokenResponse token;
        try {
            token = client.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException exception) {
            throw unavailable("obtain a service-account token", exception);
        }
        if (token == null || token.accessToken() == null) {
            throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
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

    private static DomainException unavailable(String action, RestClientException exception) {
        log.error("Keycloak call failed: {}", action, exception);
        return new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealmRole(String id, String name) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) { }

    record NewUser(String username, String email, String firstName, String lastName,
                   boolean enabled, boolean emailVerified, List<Credential> credentials) { }

    record Credential(String type, String value, boolean temporary) {
        @Override
        public String toString() {
            return "Credential[redacted]";
        }
    }
}
