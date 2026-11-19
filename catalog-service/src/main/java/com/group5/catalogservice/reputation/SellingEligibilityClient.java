package com.group5.catalogservice.reputation;

import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Machine-authenticated SELL checks before publishing a normal catalogue listing. */
@Component
public class SellingEligibilityClient {
    private final boolean enabled;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final RatingMachineTokenProvider tokens;

    public SellingEligibilityClient(RatingMachineTokenProvider tokens,
            @Value("${nexus.catalog.reputation.enabled:true}") boolean enabled,
            @Value("${nexus.catalog.reputation.base-url:http://localhost:8089}") String baseUrl,
            @Value("${nexus.catalog.reputation.connect-timeout-ms:1500}") int connectMillis,
            @Value("${nexus.catalog.reputation.read-timeout-ms:2000}") int readMillis) {
        if (connectMillis < 1 || connectMillis > 30000 || readMillis < 1 || readMillis > 30000) {
            throw new IllegalArgumentException("Reputation timeouts must be between 1 and 30000ms");
        }
        this.tokens = tokens;
        this.enabled = enabled;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = Duration.ofMillis(readMillis);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMillis))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void requireEligible(UUID userId) {
        if (!enabled) return;
        Decision decision;
        try {
            String token = tokens.accessToken();
            URI uri = URI.create(baseUrl + "/internal/v1/eligibility/" + userId + "?action=SELL");
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalStateException("Invalid reputation URL");
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").header("Authorization", "Bearer " + token).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) tokens.invalidate(token);
            if (response.statusCode() != 200 || response.body().length() > 65536) throw new IllegalStateException("Unavailable eligibility");
            decision = parse(response.body(), userId);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DomainException(CatalogErrorCode.REPUTATION_UNAVAILABLE);
        } catch (Exception failure) {
            throw new DomainException(CatalogErrorCode.REPUTATION_UNAVAILABLE);
        }
        if (!decision.eligible()) {
            if (decision.restrictedUntil() != null) {
                throw new DomainException(CatalogErrorCode.REPUTATION_RESTRICTED, decision.reason(),
                        Map.of("reason", decision.reason(), "restrictedUntil", decision.restrictedUntil(), "action", "SELL"));
            }
            throw new DomainException(CatalogErrorCode.REPUTATION_TOO_LOW, decision.reason(), Map.of("reason", decision.reason()));
        }
    }

    static Decision parse(String response, UUID userId) {
        JsonNode envelope = JsonMapper.shared().readTree(response);
        if (integer(envelope, "code") != 0) throw new IllegalStateException("Invalid envelope");
        text(envelope, "message");
        JsonNode data = envelope.path("data");
        if (!userId.toString().equalsIgnoreCase(text(data, "userId")) || !"SELL".equals(text(data, "action"))
                || !data.path("eligible").isBoolean()) throw new IllegalStateException("Invalid subject or action");
        int score = integer(data, "score"), minimum = integer(data, "minimumScore");
        if (score < 0 || score > 100 || minimum < 0 || minimum > 100
                || !Set.of("NEW", "BASIC", "TRUSTED", "TOP").contains(text(data, "trustLevel"))) {
            throw new IllegalStateException("Invalid score or trust level");
        }
        text(data, "policyVersion");
        JsonNode expiry = data.path("restrictedUntil");
        OffsetDateTime until = expiry.isMissingNode() || expiry.isNull() ? null : OffsetDateTime.parse(text(data, "restrictedUntil"));
        boolean eligible = data.path("eligible").asBoolean();
        if (eligible != (score >= minimum && until == null)) throw new IllegalStateException("Inconsistent decision");
        return new Decision(eligible, text(data, "reason"), until);
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalStateException("Invalid " + name);
        return value.intValue();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.asString().isBlank()) throw new IllegalStateException("Invalid " + name);
        return value.asString();
    }

    record Decision(boolean eligible, String reason, OffsetDateTime restrictedUntil) { }
}
