package com.group5.paymentservice.payout.evidence;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Payment-owned private storage boundary. Authorization of actors/resources stays in the service.
 */
@Component
public class SupabasePayoutEvidenceStorage implements PayoutEvidenceStorage {
    private static final Set<String> CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");
    private final ManualPayoutProperties.Evidence properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public SupabasePayoutEvidenceStorage(ManualPayoutProperties properties) {
        this(
                properties,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getEvidence().getRequestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    SupabasePayoutEvidenceStorage(ManualPayoutProperties properties, HttpClient http) {
        this.properties = properties.getEvidence();
        this.http = http;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        if (content == null
                || content.length == 0
                || contentType == null
                || !CONTENT_TYPES.contains(contentType)) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        }
        if (content.length > PayoutEvidenceValidator.MAX_BYTES) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_TOO_LARGE);
        }
        HttpRequest request =
                request(objectKey)
                        .header("Content-Type", contentType)
                        .header("x-upsert", "false")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build();
        ensurePrivateBucket();
        var response = send(request, 64 * 1024);
        if (!successful(response.status())) unavailable();
    }

    @Override
    public StoredEvidence get(String objectKey) {
        HttpRequest request = request(objectKey).GET().build();
        ensurePrivateBucket();
        var response = send(request, PayoutEvidenceValidator.MAX_BYTES);
        if (!successful(response.status()) || response.body().length == 0) unavailable();
        String contentType = response.contentType().split(";", 2)[0].trim();
        if (!CONTENT_TYPES.contains(contentType)) unavailable();
        return new StoredEvidence(response.body(), contentType);
    }

    @Override
    public void delete(String objectKey) {
        HttpRequest request = request(objectKey).DELETE().build();
        ensurePrivateBucket();
        var response = send(request, 64 * 1024);
        // Deleting an already absent rolled-back object is safe to replay.
        if (!successful(response.status()) && response.status() != 404) unavailable();
    }

    @Override
    public List<StoredObject> list(String prefix, int offset, int limit) {
        if (prefix == null
                || !prefix.isEmpty()
                        && (!prefix.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,511}")
                                || prefix.contains("//"))
                || offset < 0
                || limit < 1
                || limit > 100) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        }
        ensurePrivateBucket();
        byte[] payload =
                json.writeValueAsBytes(
                        Map.of(
                                "prefix",
                                prefix,
                                "offset",
                                offset,
                                "limit",
                                limit,
                                "sortBy",
                                Map.of("column", "name", "order", "asc")));
        HttpRequest request =
                authorizedRequest("/storage/v1/object/list/" + properties.getBucket())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
        try {
            var response = send(request, 1024 * 1024);
            if (!successful(response.status())) unavailable();
            var entries = json.readTree(response.body());
            if (entries == null || !entries.isArray() || entries.size() > limit) unavailable();
            var result = new ArrayList<StoredObject>();
            for (var entry : entries) {
                var name = entry.get("name");
                if (name == null || !name.isString()) unavailable();
                var id = entry.get("id");
                boolean directory = id == null || id.isNull();
                Instant created = null;
                if (!directory) {
                    var timestamp = entry.get("created_at");
                    if (timestamp != null && timestamp.isString()) {
                        try {
                            created = OffsetDateTime.parse(timestamp.stringValue()).toInstant();
                        } catch (DateTimeParseException ignored) {
                            /* Unknown age must never qualify for deletion. */
                        }
                    }
                }
                result.add(new StoredObject(name.stringValue(), directory, created));
            }
            return List.copyOf(result);
        } catch (tools.jackson.core.JacksonException e) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        }
    }

    private HttpRequest.Builder request(String objectKey) {
        if (!configured()) throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        if (objectKey == null
                || !objectKey.matches("[A-Za-z0-9][A-Za-z0-9/_.-]{0,511}")
                || objectKey.contains("..")
                || objectKey.contains("//")
                || objectKey.endsWith("/")) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_INVALID);
        }
        return authorizedRequest("/storage/v1/object/" + properties.getBucket() + "/" + objectKey);
    }

    private void ensurePrivateBucket() {
        // Provisioning is external. Never create a bucket or silently accept a public bucket.
        HttpRequest request =
                authorizedRequest("/storage/v1/bucket/" + properties.getBucket()).GET().build();
        try {
            var response = send(request, 64 * 1024);
            if (!successful(response.status())) unavailable();
            var bucket = json.readTree(response.body());
            var visibility = bucket == null ? null : bucket.get("public");
            if (visibility == null || !visibility.isBoolean() || visibility.booleanValue())
                unavailable();
        } catch (tools.jackson.core.JacksonException e) {
            unavailable();
        }
    }

    private HttpRequest.Builder authorizedRequest(String path) {
        if (!configured()) throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        String url = properties.getUrl().replaceAll("/+$", "") + path;
        try {
            var builder =
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(properties.getRequestTimeout())
                            .header("apikey", properties.getKey());
            // Modern sb_secret keys authenticate through apikey; legacy service_role keys are JWTs.
            if (properties.getKey().startsWith("eyJ")) {
                builder.header("Authorization", "Bearer " + properties.getKey());
            }
            return builder;
        } catch (IllegalArgumentException e) {
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        }
    }

    private StorageResponse send(HttpRequest request, int maxBytes) {
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future = null;
        try {
            future =
                    http.sendAsync(request, ignored -> new BoundedEvidenceBodySubscriber(maxBytes));
            // Bound the complete exchange, including a server that sends headers and then stalls.
            var response =
                    future.get(
                            Math.max(1, properties.getRequestTimeout().toMillis()),
                            TimeUnit.MILLISECONDS);
            if (response.body() == null || response.body().length > maxBytes) unavailable();
            return new StorageResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers()
                            .firstValue("Content-Type")
                            .orElse("application/octet-stream"));
        } catch (InterruptedException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        } catch (ExecutionException
                | TimeoutException
                | CancellationException
                | IllegalArgumentException e) {
            if (future != null) future.cancel(true);
            // Never propagate upstream exception messages, response bodies, object paths or
            // credentials.
            throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
        }
    }

    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    private static void unavailable() {
        throw new DomainException(PayoutEvidenceErrorCode.EVIDENCE_UNAVAILABLE);
    }

    private record StorageResponse(int status, byte[] body, String contentType) {}
}
