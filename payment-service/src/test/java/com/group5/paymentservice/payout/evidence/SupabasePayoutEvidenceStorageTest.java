package com.group5.paymentservice.payout.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class SupabasePayoutEvidenceStorageTest {
    private final ManualPayoutProperties properties = settings();
    private final HttpClient http = mock(HttpClient.class);
    private final SupabasePayoutEvidenceStorage storage =
            new SupabasePayoutEvidenceStorage(properties, http);

    @Test
    void uploadsOnlyToConfiguredPrivateObjectPathAndNeverUpserts() throws Exception {
        respond(200, "application/json", "{}");
        storage.put("payout/attempt/evidence.pdf", pdf(), "application/pdf");
        HttpRequest request = request();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString())
                .isEqualTo(
                        "https://project.example.test/storage/v1/object/payout-evidence/payout/attempt/evidence.pdf");
        assertThat(request.uri().toString()).doesNotContain("public", "sign", "sb_secret");
        assertThat(request.headers().firstValue("apikey")).contains("sb_secret_test_only");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(request.headers().firstValue("x-upsert")).contains("false");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/pdf");
    }

    @Test
    void readsPrivateBytesWithoutReturningAStorageUrl() throws Exception {
        respond(
                200,
                "application/pdf; charset=binary",
                new String(pdf(), StandardCharsets.US_ASCII));
        var file = storage.get("payout/attempt/evidence.pdf");
        assertThat(file.content()).isEqualTo(pdf());
        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(request().method()).isEqualTo("GET");
    }

    @Test
    void legacyServiceRoleTokenIsOnlyAddedToBackendHeaders() throws Exception {
        properties.getEvidence().setKey("eyJtest_only.payload.signature");
        respond(200, "application/json", "{}");
        storage.put("payout/attempt/evidence.pdf", pdf(), "application/pdf");
        HttpRequest request = request();
        assertThat(request.headers().firstValue("Authorization"))
                .contains("Bearer eyJtest_only.payload.signature");
        assertThat(request.uri().toString()).doesNotContain("eyJ");
    }

    @Test
    void absentBucketOrCredentialsFailsAsSafe503WithoutProviderDetail() throws Exception {
        respond(404, "application/json", "private bucket URL and account data must never leak");
        assertThatThrownBy(() -> storage.get("payout/attempt/evidence.pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo(503);
                            assertThat(error.getMessage())
                                    .doesNotContain("URL", "account", "project.example");
                        });
        clearInvocations(http);
        properties.getEvidence().setKey("");
        assertThat(storage.configured()).isFalse();
        assertThatThrownBy(
                        () -> storage.put("payout/attempt/evidence.pdf", pdf(), "application/pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        verifyNoInteractions(http);
    }

    @Test
    void rejectsTraversalAndExternalUrlsBeforeAnyRequest() {
        for (String key :
                List.of(
                        "../other/evidence.pdf",
                        "https://attacker.test/evidence",
                        "payout/%2e%2e/evidence",
                        "/root/file.pdf")) {
            assertThatThrownBy(() -> storage.get(key))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            error -> assertThat(error.getCode()).isEqualTo(400));
        }
        verifyNoInteractions(http);
    }

    @Test
    void compensationDeleteAcceptsAnAlreadyMissingObject() throws Exception {
        respond(404, "application/json", "{}");
        storage.delete("payout/attempt/evidence.pdf");
        assertThat(request().method()).isEqualTo("DELETE");
    }

    @Test
    void transportFailureDoesNotExposeUpstreamException() throws Exception {
        when(http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IOException("secret provider header and bank details")));
        assertThatThrownBy(() -> storage.get("payout/attempt/evidence.pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        error -> {
                            assertThat(error.getErrorCode())
                                    .isEqualTo("PAYMENT_MANUAL_PAYOUT_EVIDENCE_UNAVAILABLE");
                            assertThat(error.getMessage()).doesNotContain("secret", "bank");
                        });
    }

    @Test
    void publicOrMissingBucketIsRejectedBeforeAnyObjectUpload() throws Exception {
        var publicBucket =
                response(200, "application/json", "{\"name\":\"payout-evidence\",\"public\":true}");
        when(http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(publicBucket));
        assertThatThrownBy(
                        () -> storage.put("payout/attempt/evidence.pdf", pdf(), "application/pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .sendAsync(
                        captor.capture(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(captor.getValue().uri().getPath())
                .isEqualTo("/storage/v1/bucket/payout-evidence");
        assertThat(captor.getValue().method()).isEqualTo("GET");
    }

    @Test
    void aNotYetProvisionedBucketFails503WithoutCreatingIt() throws Exception {
        var missing = response(404, "application/json", "{\"message\":\"Bucket not found\"}");
        when(http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(missing));
        assertThatThrownBy(
                        () -> storage.put("payout/attempt/evidence.pdf", pdf(), "application/pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .sendAsync(
                        captor.capture(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(captor.getValue().method()).isEqualTo("GET");
    }

    @Test
    void listingUsesPrivateApiAndKeepsUnknownAgeObjectsSafe() throws Exception {
        respond(
                200,
                "application/json",
                """
                [{"name":"folder","id":null,"created_at":null},
                 {"name":"file.pdf","id":"object-id","created_at":"2026-11-16T00:00:00Z"},
                 {"name":"unknown.pdf","id":"other-id","created_at":"unparseable"}]
                """);
        var objects = storage.list("", 0, 100);
        assertThat(objects).hasSize(3);
        assertThat(objects.get(0).directory()).isTrue();
        assertThat(objects.get(1).createdAt()).hasToString("2026-11-16T00:00:00Z");
        assertThat(objects.get(2).createdAt()).isNull();
        assertThat(request().uri().getPath()).isEqualTo("/storage/v1/object/list/payout-evidence");
    }

    @Test
    void aStalledBodyIsCancelledAtTheConfiguredDeadline() {
        properties.getEvidence().setRequestTimeout(Duration.ofMillis(5));
        var unfinished = new CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(unfinished);
        assertThatThrownBy(() -> storage.get("payout/attempt/evidence.pdf"))
                .isInstanceOfSatisfying(
                        DomainException.class, error -> assertThat(error.getCode()).isEqualTo(503));
        assertThat(unfinished.isCancelled()).isTrue();
    }

    private static ManualPayoutProperties settings() {
        var properties = new ManualPayoutProperties();
        properties.getEvidence().setUrl("https://project.example.test");
        properties.getEvidence().setKey("sb_secret_test_only");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String contentType, String content) throws Exception {
        var bucket =
                response(
                        200, "application/json", "{\"name\":\"payout-evidence\",\"public\":false}");
        var response = response(status, contentType, content);
        when(http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(
                        CompletableFuture.completedFuture(bucket),
                        CompletableFuture.completedFuture(response));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String contentType, String content) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(content.getBytes(StandardCharsets.US_ASCII));
        when(response.headers())
                .thenReturn(
                        HttpHeaders.of(
                                Map.of("Content-Type", List.of(contentType)), (a, b) -> true));
        return response;
    }

    private HttpRequest request() throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2))
                .sendAsync(
                        captor.capture(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        return captor.getAllValues().getLast();
    }

    private byte[] pdf() {
        return "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    }
}
