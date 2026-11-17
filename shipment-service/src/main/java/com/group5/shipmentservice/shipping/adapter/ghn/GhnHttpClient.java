package com.group5.shipmentservice.shipping.adapter.ghn;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.GhnProperties;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import javax.net.ssl.SSLException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class GhnHttpClient {
    private static final Logger log = LoggerFactory.getLogger(GhnHttpClient.class);
    private final GhnProperties properties;
    private final ShippingProperties shipping;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;

    GhnHttpClient(GhnProperties properties, ShippingProperties shipping, String token, ObjectMapper mapper) {
        this.properties = properties;
        this.shipping = shipping;
        this.token = token;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(shipping.apiTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JsonNode get(String path, ShippingDeadline deadline) {
        return request(path, null, deadline, shipping.retryLimit());
    }

    JsonNode post(String path, Object body, ShippingDeadline deadline) {
        return request(path, mapper.writeValueAsString(body), deadline, shipping.retryLimit());
    }

    /** A create timeout is uncertain: the adapter must look up the client code before retrying. */
    JsonNode postOnce(String path, Object body, ShippingDeadline deadline) {
        return request(path, mapper.writeValueAsString(body), deadline, 0);
    }

    JsonNode findByClientCode(String clientOrderCode, ShippingDeadline deadline) {
        try {
            return get("/shiip/public-api/v2/shipping-order/detail-by-client-code?client_order_code="
                    + java.net.URLEncoder.encode(clientOrderCode, java.nio.charset.StandardCharsets.UTF_8), deadline);
        } catch (GhnProviderException error) {
            if (error.isOrderNotFound()) return null;
            throw error;
        }
    }

    private JsonNode request(String path, String body, ShippingDeadline deadline, int retryLimit) {
        for (int attempt = 0; attempt <= retryLimit; attempt++) {
            Duration timeout = attemptTimeout(deadline);
            HttpRequest.Builder builder = HttpRequest.newBuilder(properties.baseUrl().resolve(path))
                    .timeout(timeout)
                    .header("Token", token)
                    .header("ShopId", properties.shopId().toString())
                    .header("Accept", "application/json");
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            }
            var pending = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    logTransientFailure(status, response.body());
                    retryOrThrow(attempt, retryLimit, deadline, retryDelay(response, attempt));
                    continue;
                }
                if (status == 401 || status == 403) {
                    throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
                }
                JsonNode envelope = parse(response.body());
                if (status >= 400) {
                    throw providerError(path, status, envelope);
                }
                if (status != 200 || envelope == null || !envelope.isObject()) {
                    throw invalidResponse();
                }
                JsonNode code = envelope.path("code");
                if (!code.isIntegralNumber()) {
                    throw invalidResponse();
                }
                int providerStatus = code.intValue();
                if (providerStatus == 429 || providerStatus >= 500) {
                    logProviderFailure(providerStatus, envelope);
                    retryOrThrow(attempt, retryLimit, deadline, backoff(attempt));
                    continue;
                }
                if (providerStatus != 200) {
                    throw providerError(path, providerStatus, envelope);
                }
                JsonNode data = envelope.path("data");
                if (data.isNull() && path.startsWith("/shiip/public-api/v3/master-data/ward/all-by-province-id?")) {
                    throw new DomainException(ShipmentErrorCode.INVALID_ADDRESS);
                }
                if (data.isMissingNode() || data.isNull()) {
                    throw invalidResponse();
                }
                return data;
            } catch (TimeoutException error) {
                pending.cancel(true);
                retryOrThrow(attempt, retryLimit, deadline, backoff(attempt));
            } catch (ExecutionException error) {
                pending.cancel(true);
                Throwable cause = error.getCause();
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof SSLException) {
                    throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
                }
                if (!(cause instanceof IOException)) {
                    throw unavailable();
                }
                retryOrThrow(attempt, retryLimit, deadline, backoff(attempt));
            } catch (InterruptedException error) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw unavailable();
            }
        }
        throw unavailable();
    }

    private Duration attemptTimeout(ShippingDeadline deadline) {
        Duration remaining = deadline.remainingOrThrow();
        Duration configured = Duration.ofMillis(properties.httpTimeoutMs());
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt, 20);
        return Duration.ofMillis(Math.min(60_000, shipping.retryBackoffMs() * multiplier));
    }

    private Duration retryDelay(HttpResponse<?> response, int attempt) {
        Duration backoff = backoff(attempt);
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        try {
            Duration providerDelay;
            if (retryAfter.matches("[0-9]+")) {
                providerDelay = Duration.ofSeconds(Long.parseLong(retryAfter));
            } else {
                providerDelay = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME));
            }
            return providerDelay.compareTo(backoff) > 0 ? providerDelay : backoff;
        } catch (RuntimeException error) {
            return backoff;
        }
    }

    private void retryOrThrow(int attempt, int retryLimit, ShippingDeadline deadline, Duration delay) {
        if (attempt >= retryLimit || deadline.remainingOrThrow().compareTo(delay.plusMillis(1)) <= 0) {
            throw unavailable();
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw invalidResponse();
        }
        try {
            return mapper.readTree(body);
        } catch (RuntimeException error) {
            throw invalidResponse();
        }
    }

    private DomainException providerError(String path, int status, JsonNode envelope) {
        String code = envelope.path("code_message").asString("");
        String message = envelope.path("message").asString("");
        logProviderFailure(status, envelope);
        if (status == 401 || status == 403 || code.startsWith("CLIENT_") || code.contains("TOKEN")) {
            return new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
        }
        if (path.startsWith("/shiip/public-api/v2/shipping-order/detail-by-client-code?")) {
            String normalized = message.strip().toLowerCase(java.util.Locale.ROOT);
            boolean missing = (status == 400 || status == 404) && ("ORDER_NOT_FOUND".equals(code)
                    || "ORDER_NOT_EXIST".equals(code) || normalized.contains("đơn hàng không tồn tại")
                    || normalized.contains("order not found") || normalized.contains("order does not exist"));
            return new GhnProviderException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE, code, missing);
        }
        if (path.equals("/shiip/public-api/v2/shipping-order/create") && "TO_ADDRESS_CONFLICT".equals(code)) {
            return new GhnProviderException(ShipmentErrorCode.INVALID_ADDRESS, code, false);
        }
        if (path.equals("/shiip/public-api/v2/switch-status/cancel")) {
            return new DomainException(ShipmentErrorCode.CARRIER_CANCELLATION_REJECTED);
        }
        if (path.startsWith("/shiip/public-api/v2/shipping-order/detail?")) {
            return invalidResponse();
        }
        if (List.of("ROUTE_NOT_FOUND_SERVICE", "SERVICE_NOT_FOUND_CONFIG_FEE").contains(code)
                || envelope.path("message").asString("").toLowerCase(java.util.Locale.ROOT).contains("route not found service")) {
            return new DomainException(ShipmentErrorCode.NO_SERVICE);
        }
        if ("PHONE_INVALID".equals(code)) {
            return new DomainException(ShipmentErrorCode.INVALID_REQUEST);
        }
        if (status == 400 || status == 404 || status == 422) {
            return new DomainException(ShipmentErrorCode.INVALID_ADDRESS);
        }
        return invalidResponse();
    }

    private void logTransientFailure(int status, String body) {
        try {
            logProviderFailure(status, parse(body));
        } catch (RuntimeException ignored) {
            log.warn("GHN rejected request status={}", status);
        }
    }

    private void logProviderFailure(int status, JsonNode envelope) {
        // The provider detail is diagnostic only. Never include it in DomainException/client properties.
        String code = envelope.path("code_message").asString("").replaceAll("[^A-Za-z0-9_-]", "");
        String diagnostic = envelope.path("message").asString("").replace(token, "[redacted]").replaceAll("[\\r\\n]", " ");
        log.warn("GHN rejected request status={} providerCode={} message={}", status,
                code, diagnostic.substring(0, Math.min(diagnostic.length(), 500)));
    }

    private DomainException invalidResponse() {
        return new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
    }

    private DomainException unavailable() {
        return new DomainException(ShipmentErrorCode.CARRIER_UNAVAILABLE);
    }
}
