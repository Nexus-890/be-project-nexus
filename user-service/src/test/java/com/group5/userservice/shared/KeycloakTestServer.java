package com.group5.userservice.shared;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A disposable Keycloak with the project realm and seed script, shared by the test JVM. */
public final class KeycloakTestServer {
    public static final String CLIENT_SECRET = random(32);
    public static final String DEMO_ADMIN = "admin@nexus.local";
    public static final String DEMO_ADMIN_PASSWORD = random(16);
    private static final String ADMIN = "test-admin";
    private static final String ADMIN_PASSWORD = random(24);
    private static final String REDIRECT = "http://localhost:3000/user-service-test-callback";
    private static final Path KEYCLOAK_DIR = Path.of("..", "infra", "local-dev", "keycloak").toAbsolutePath().normalize();
    private static final Set<UUID> DEMO_USERS = Set.of(
            UUID.fromString("68000000-0000-4000-8000-000000000001"),
            UUID.fromString("68000000-0000-4000-8000-000000000002"),
            UUID.fromString("68000000-0000-4000-8000-000000000003"));
    private static final Pattern FORM = Pattern.compile("<form\\b[^>]*>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]*)\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.7.5")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath(KEYCLOAK_DIR.resolve("realm-nexus.json")),
                    "/opt/keycloak/data/import/nexus-realm.json")
            .withCopyFileToContainer(MountableFile.forHostPath(KEYCLOAK_DIR.resolve("seed-users.sh")),
                    "/opt/nexus/seed-users.sh")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/nexus/.well-known/openid-configuration")
                    .forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
        seed();
    }

    private KeycloakTestServer() {
    }

    public static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    public static String issuer() {
        return baseUrl() + "/realms/nexus";
    }

    public static String jwkSetUri() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    public static void deleteRegisteredUsers() {
        for (JsonNode user : admin("GET", "/users?briefRepresentation=true&max=1000", null)) {
            UUID id = UUID.fromString(user.get("id").asString());
            if (!DEMO_USERS.contains(id)) {
                admin("DELETE", "/users/" + id, null);
            }
        }
    }

    public static Optional<UUID> userIdByEmail(String email) {
        JsonNode users = admin("GET", "/users?exact=true&briefRepresentation=true&email="
                + URLEncoder.encode(email, StandardCharsets.UTF_8), null);
        return users.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(users.get(0).get("id").asString()));
    }

    public static int registeredUserCount() {
        int count = 0;
        for (JsonNode user : admin("GET", "/users?briefRepresentation=true&max=1000", null)) {
            if (!DEMO_USERS.contains(UUID.fromString(user.get("id").asString()))) {
                count++;
            }
        }
        return count;
    }

    public static List<String> realmRoles(UUID userId) {
        List<String> roles = new ArrayList<>();
        admin("GET", "/users/" + userId + "/role-mappings/realm", null)
                .forEach(role -> roles.add(role.get("name").asString()));
        return roles;
    }

    /** Signs in through the browser Authorization Code + PKCE flow and returns the access token. */
    public static String signIn(String username, String password) throws Exception {
        String verifier = random(48);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        Map<String, String> cookies = new LinkedHashMap<>();
        HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/auth?"
                + form(Map.of("client_id", "nexus-web", "response_type", "code", "scope", "openid",
                "redirect_uri", REDIRECT, "state", "test-state", "code_challenge", challenge,
                "code_challenge_method", "S256")))).GET(), cookies);
        String action = loginAction(page.body());

        HttpResponse<String> login = send(HttpRequest.newBuilder(URI.create(action))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "username", username, "password", password, "credentialId", "")))), cookies);
        String location = login.headers().firstValue("Location").orElse("");
        if (login.statusCode() != 302 || !location.startsWith(REDIRECT)) {
            throw new IllegalStateException("Keycloak sign-in failed with HTTP " + login.statusCode());
        }
        String code = query(URI.create(location)).get("code");

        HttpResponse<String> token = send(HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "authorization_code", "client_id", "nexus-web", "redirect_uri", REDIRECT,
                        "code", code, "code_verifier", verifier)))), cookies);
        if (token.statusCode() != 200) {
            throw new IllegalStateException("Keycloak code exchange failed with HTTP " + token.statusCode());
        }
        return JSON.readTree(token.body()).get("access_token").asString();
    }

    private static void seed() {
        try {
            Container.ExecResult result = KEYCLOAK.execInContainer(ExecConfig.builder()
                    .command(new String[] {"/bin/bash", "/opt/nexus/seed-users.sh", "--container"})
                    .envVars(Map.of(
                            "KEYCLOAK_SERVER_URL", "http://localhost:8080",
                            "KEYCLOAK_ADMIN", ADMIN,
                            "KEYCLOAK_ADMIN_PASSWORD", ADMIN_PASSWORD,
                            "DEMO_ADMIN_PASSWORD", DEMO_ADMIN_PASSWORD,
                            "DEMO_SELLER_PASSWORD", random(16),
                            "DEMO_BUYER_PASSWORD", random(16),
                            "KEYCLOAK_USER_SERVICE_CLIENT_SECRET", CLIENT_SECRET))
                    .build());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("seed-users.sh failed: " + result.getStderr());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot seed the Keycloak test realm", exception);
        }
    }

    private static JsonNode admin(String method, String path, String json) {
        try {
            HttpResponse<String> token = send(HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/realms/master/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "password",
                            "client_id", "admin-cli", "username", ADMIN, "password", ADMIN_PASSWORD)))), null);
            String accessToken = JSON.readTree(token.body()).get("access_token").asString();
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/realms/nexus" + path))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json));
            HttpResponse<String> response = send(request, null);
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " returned HTTP " + response.statusCode());
            }
            return response.body().isBlank() ? JSON.createArrayNode() : JSON.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException("Keycloak admin call failed: " + method + " " + path, exception);
        }
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> cookies)
            throws Exception {
        if (cookies != null && !cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response = HTTP.send(request.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (cookies != null) {
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    cookies.put(pair.substring(0, equals).strip(), pair.substring(equals + 1).strip());
                }
            }
        }
        return response;
    }

    private static String loginAction(String html) {
        Matcher forms = FORM.matcher(html);
        while (forms.find()) {
            String tag = forms.group();
            Matcher action = ACTION.matcher(tag);
            if (tag.contains("id=\"kc-form-login\"") && action.find()) {
                return action.group(1).replace("&amp;", "&");
            }
        }
        throw new IllegalStateException("Expected the Keycloak login form");
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
