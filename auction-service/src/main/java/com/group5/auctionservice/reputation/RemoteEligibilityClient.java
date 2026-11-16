package com.group5.auctionservice.reputation;

import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import feign.Response;
import feign.FeignException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class RemoteEligibilityClient implements EligibilityClient {
    private final RatingEligibilityHttpClient http;
    private final RatingMachineTokenProvider tokens;
    private final CircuitBreaker breaker;

    public RemoteEligibilityClient(RatingEligibilityHttpClient http, RatingMachineTokenProvider tokens,
                                   Resilience4JCircuitBreakerFactory factory) {
        this.http = http;
        this.tokens = tokens;
        this.breaker = factory.create("auctionReputation");
    }

    @Override
    public Decision check(UUID userId, EligibilityAction action) {
        return breaker.run(() -> fetch(userId, action), failure -> {
            throw new DomainException(AuctionErrorCode.AUCTION_REPUTATION_UNAVAILABLE);
        });
    }

    private Decision fetch(UUID userId, EligibilityAction action) {
        String token = tokens.accessToken();
        try (Response response = http.check(userId, action, "Bearer " + token)) {
            if (response.status() == 401 || response.status() == 403) {
                tokens.invalidate(token);
            }
            if (response.status() != 200 || response.body() == null) {
                throw invalid();
            }
            byte[] bytes = response.body().asInputStream().readNBytes(65537);
            if (bytes.length > 65536) {
                throw invalid();
            }
            return parse(new String(bytes, StandardCharsets.UTF_8), userId, action);
        } catch (FeignException failure) {
            if (failure.status() == 401 || failure.status() == 403) {
                tokens.invalidate(token);
            }
            throw invalid();
        } catch (Exception failure) {
            throw invalid();
        }
    }

    static Decision parse(String json, UUID userId, EligibilityAction action) {
        JsonNode body = JsonMapper.shared().readTree(json);
        if (body == null || !body.isObject() || integer(body, "code") != 0) {
            throw invalid();
        }
        text(body, "message");
        JsonNode data = body.get("data");
        if (data == null || !data.isObject()) {
            throw invalid();
        }
        JsonNode eligible = data.get("eligible");
        if (eligible == null || !eligible.isBoolean()) {
            throw invalid();
        }
        String returnedUserId = text(data, "userId");
        UUID parsedUserId = UUID.fromString(returnedUserId);
        if (!parsedUserId.toString().equalsIgnoreCase(returnedUserId)) {
            throw invalid();
        }
        Decision decision = new Decision(parsedUserId,
                EligibilityAction.valueOf(text(data, "action")), eligible.booleanValue(),
                integer(data, "score"), text(data, "trustLevel"), integer(data, "minimumScore"),
                text(data, "reason"), text(data, "policyVersion"));
        ReputationGuard.validate(userId, action, decision);
        return decision;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid();
        }
        return value.stringValue();
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Rating eligibility is unavailable");
    }
}
