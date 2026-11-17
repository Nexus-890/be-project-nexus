package com.group5.auctionservice.reputation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class RatingMachineTokenProvider {
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private String cachedToken;
    private Instant refreshAt = Instant.MIN;

    public RatingMachineTokenProvider(
            @Value("${nexus.auction.reputation.token-uri:http://localhost:18080/realms/nexus/protocol/openid-connect/token}") String tokenUri,
            @Value("${nexus.auction.reputation.client-id:nexus-auction-service}") String clientId,
            @Value("${nexus.auction.reputation.client-secret:}") String clientSecret,
            @Value("${nexus.auction.reputation.token-timeout-ms:1500}") long timeoutMillis,
            Clock clock) {
        if (timeoutMillis < 1 || timeoutMillis > 30000) {
            throw new IllegalArgumentException("Token timeout must be between 1 and 30000 milliseconds");
        }
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedToken != null && now.isBefore(refreshAt)) {
            return cachedToken;
        }
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw unavailable();
        }
        try {
            URI uri = URI.create(tokenUri);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw unavailable();
            }
            String form = "grant_type=client_credentials&client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret);
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 65536) {
                throw unavailable();
            }
            JsonNode body = JsonMapper.shared().readTree(response.body());
            JsonNode token = body == null ? null : body.get("access_token");
            JsonNode expires = body == null ? null : body.get("expires_in");
            JsonNode type = body == null ? null : body.get("token_type");
            if (body == null || !body.isObject() || token == null || !token.isString()
                    || token.stringValue().isBlank() || token.stringValue().chars().anyMatch(Character::isWhitespace)
                    || type == null || !type.isString() || !"Bearer".equalsIgnoreCase(type.stringValue())
                    || expires == null || !expires.isIntegralNumber() || !expires.canConvertToLong()
                    || expires.longValue() < 1 || expires.longValue() > 86400) {
                throw unavailable();
            }
            long lifetime = expires.longValue();
            refreshAt = now.plusSeconds(lifetime).minusMillis(Math.min(30000, lifetime * 100));
            if (!clock.instant().isBefore(refreshAt)) {
                throw unavailable();
            }
            cachedToken = token.stringValue();
            return cachedToken;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    public synchronized void invalidate(String token) {
        if (token != null && token.equals(cachedToken)) {
            cachedToken = null;
            refreshAt = Instant.MIN;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Rating machine authentication is unavailable");
    }
}
