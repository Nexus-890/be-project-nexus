package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** HTTP thật cho token/realm; không xem default hay null là evidence của current values. */
class KeycloakRealmConfigHttpTest {
    @TempDir Path directory;
    private HttpServer server;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private final AtomicInteger realmCalls = new AtomicInteger();
    private Map<String, Object> realm;
    private String tokenBody = "{\"access_token\":\"realm-reader\"}";
    private int tokenStatus = 200;
    private int realmStatus = 200;
    private volatile String lastForm;
    private volatile String lastAuthorization;

    @BeforeEach
    void startRealm() throws Exception {
        realm = new LinkedHashMap<>(Map.of("ssoSessionIdleTimeout", 4500, "bruteForceProtected", true,
                "failureFactor", 8, "passwordPolicy", "length(12) and digits(1)", "accessTokenLifespan", 90));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/nexus/protocol/openid-connect/token", exchange -> {
            tokenCalls.incrementAndGet();
            lastForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(tokenStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/admin/realms/nexus", exchange -> {
            realmCalls.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = JsonMapper.shared().writeValueAsBytes(realm);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(realmStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopRealm() { if (server != null) server.stop(0); }

    @Test
    void listsAllFourRealmValuesSeparatelyFromConfigServerAndSrsDefaults() throws Exception {
        var repository = mock(RuntimeEnvironmentRepository.class);
        when(repository.baseline(anyString())).thenAnswer(call -> {
            var environment = new Environment(call.getArgument(0), "default");
            environment.add(new PropertySource("config-source", Map.of("nexus.iam.max-login-attempts", 3,
                    "nexus.iam.session-timeout-minutes", 30, "nexus.iam.access-token-expiry-minutes", 60,
                    "nexus.iam.password-policy", "STANDARD")));
            return environment;
        });
        try (var store = new SystemConfigStore(directory.resolve("state.json").toString(), JsonMapper.shared())) {
            var service = new SystemConfigService(store, repository, mock(SystemConfigRefresher.class),
                    mock(EventEnvelopeFactory.class), client("secret &value"));
            var values = service.list();
            assertThat(values).hasSize(57);
            assertThat(values).filteredOn(row -> row.key().equals("SESSION_TIMEOUT_MINUTES"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.value()).isEqualTo(new BigDecimal("75"));
                        assertThat(row.configuredValue()).isEqualTo(30);
                        assertThat(row.defaultValue()).isEqualTo(30);
                        assertThat(row.applyMode()).isEqualTo("KEYCLOAK_MANAGED");
                    });
            assertThat(values).filteredOn(row -> row.key().equals("MAX_LOGIN_ATTEMPTS"))
                    .singleElement().satisfies(row -> {
                        assertThat(row.value()).isEqualTo(8);
                        assertThat(row.configuredValue()).isEqualTo(3);
                        assertThat(row.defaultValue()).isEqualTo(5);
                    });
            assertThat(values).filteredOn(row -> row.key().equals("PASSWORD_POLICY"))
                    .singleElement().extracting(SystemConfigService.ConfigResponse::value)
                    .isEqualTo("length(12) and digits(1)");
            assertThat(values).filteredOn(row -> row.key().equals("ACCESS_TOKEN_EXPIRY_MINUTES"))
                    .singleElement().extracting(SystemConfigService.ConfigResponse::value)
                    .isEqualTo(new BigDecimal("1.5"));
            assertThat(tokenCalls).hasValue(1);
            assertThat(realmCalls).hasValue(1);
            assertThat(lastForm).contains("grant_type=client_credentials", "client_id=config-service",
                    "client_secret=secret+%26value");
            assertThat(lastAuthorization).isEqualTo("Bearer realm-reader");
        }
    }

    @Test
    void nextReadObservesRealmEditsWithoutRestartAndKeepsFractionalMinutes() {
        var client = client("secret");
        assertThat(client.currentValues()).containsEntry("MAX_LOGIN_ATTEMPTS", 8);
        realm.put("ssoSessionIdleTimeout", 75);
        realm.put("accessTokenLifespan", 150);
        realm.put("failureFactor", 11);
        realm.put("passwordPolicy", "length(16)");
        assertThat(client.currentValues()).containsEntry("MAX_LOGIN_ATTEMPTS", 11)
                .containsEntry("SESSION_TIMEOUT_MINUTES", new BigDecimal("1.25"))
                .containsEntry("ACCESS_TOKEN_EXPIRY_MINUTES", new BigDecimal("2.5"))
                .containsEntry("PASSWORD_POLICY", "length(16)");
        assertThat(realmCalls).hasValue(2);
    }

    @Test
    void disabledBruteForceReportsNoAttemptLimitAndEmptyPolicyIsPreserved() {
        realm.put("bruteForceProtected", false);
        realm.put("passwordPolicy", "");
        assertThat(client("secret").currentValues()).containsEntry("MAX_LOGIN_ATTEMPTS", 0)
                .containsEntry("PASSWORD_POLICY", "");
    }

    @Test
    void missingCredentialFailsBeforeNetwork() {
        unavailable(() -> client("").currentValues());
        assertThat(tokenCalls).hasValue(0);
        assertThat(realmCalls).hasValue(0);
    }

    @Test
    void tokenFailureNeverRequestsRealmOrLeaksProviderBody() {
        for (int status : new int[]{401, 403, 500}) {
            tokenStatus = status;
            tokenBody = "{\"error\":\"sensitive provider detail\"}";
            unavailable(() -> client("secret").currentValues());
        }
        assertThat(realmCalls).hasValue(0);
    }

    @Test
    void missingOrBlankAccessTokenIsUnavailable() {
        for (String body : new String[]{"{}", "{\"access_token\":\"\"}", "{\"access_token\":8}"}) {
            tokenBody = body;
            unavailable(() -> client("secret").currentValues());
        }
        assertThat(realmCalls).hasValue(0);
    }

    @Test
    void realmPermissionOrProviderFailureIsUnavailable() {
        for (int status : new int[]{403, 404, 500}) {
            realmStatus = status;
            unavailable(() -> client("secret").currentValues());
        }
    }

    @Test
    void malformedOrMissingRealmFieldsNeverFallBackToSrsValues() {
        var baseline = new LinkedHashMap<>(realm);
        for (String field : baseline.keySet()) {
            realm = new LinkedHashMap<>(baseline);
            realm.remove(field);
            unavailable(() -> client("secret").currentValues());
        }
        for (Object bad : new Object[]{"60", -1, 1.5}) {
            realm = new LinkedHashMap<>(baseline);
            realm.put("accessTokenLifespan", bad);
            unavailable(() -> client("secret").currentValues());
        }
    }

    @Test
    void unreachableRealmIsUnavailable() {
        var client = client("secret");
        server.stop(0);
        server = null;
        unavailable(client::currentValues);
    }

    private KeycloakRealmConfigClient client(String secret) {
        return new KeycloakRealmConfigClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "nexus", "config-service", secret, Duration.ofSeconds(2));
    }

    private static void unavailable(Runnable request) {
        assertThatThrownBy(request::run).isInstanceOf(DomainException.class)
                .hasMessageNotContaining("secret").hasMessageNotContaining("sensitive provider detail")
                .extracting("errorCode").isEqualTo("CONFIG_IDENTITY_PROVIDER_UNAVAILABLE");
    }
}
