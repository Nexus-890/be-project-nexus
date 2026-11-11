package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.provider.sepay.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import jakarta.servlet.Filter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Acceptance tests for the unmodified provider delivery, not a generated test vector. */
@EnabledIfEnvironmentVariable(named = "SEPAY_CAPTURE_TEST", matches = "true",
        disabledReason = "Enable only with a captured SePay body, its original headers and matching secret")
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {SepayWebhookControllerTest.TestApplication.class, WebhookCapturedDeliveryTest.CapturedClock.class},
        initializers = SepayWebhookControllerTest.PaymentYaml.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.payment.sepay.bank-account=0000000002", "nexus.payment.sepay.account-name=NEXUS TEST"
})
class WebhookCapturedDeliveryTest {
    @Autowired private WebApplicationContext context;
    @Autowired private PaymentSessionRepository sessions;
    private byte[] body;
    private String signature;
    private String timestamp;
    private WebhookSignatureVerifier verifier;

    @BeforeEach
    void loadCapturedDelivery() throws IOException {
        String secret = requiredEnvironment("SEPAY_WEBHOOK_SECRET");
        signature = requiredEnvironment("SEPAY_CAPTURE_SIGNATURE");
        timestamp = requiredEnvironment("SEPAY_CAPTURE_TIMESTAMP");

        String externalBody = System.getenv("SEPAY_CAPTURE_BODY_PATH");
        Path sample = externalBody == null || externalBody.isBlank()
                ? Path.of(System.getProperty("basedir", "."), "..", "docs", "api", "sepay-webhook-sample.json").toAbsolutePath()
                : Path.of(externalBody).toAbsolutePath();
        assertThat(Files.isRegularFile(sample))
                .as("Provide an actual SePay delivery in the repository fixture or SEPAY_CAPTURE_BODY_PATH")
                .isTrue();
        body = Files.readAllBytes(sample);
        assertThat(body.length).as("The captured body must not be empty").isPositive();

        // A historical delivery must still exercise the timestamp check, at its actual signing time.
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        verifier = new WebhookSignatureVerifier(secret, Clock.fixed(signedAt, ZoneOffset.UTC));
        reset(sessions);
    }

    @Test
    void capturedDeliveryReachesHttpControllerWithoutTokenAndChangedSignatureIs401() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        mvc.perform(post(SepayWebhookControllerTest.PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", signature).header("X-SePay-Timestamp", timestamp))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
        reset(sessions);
        String changed = signature.substring(0, signature.length() - 1) + (signature.endsWith("0") ? "1" : "0");
        mvc.perform(post(SepayWebhookControllerTest.PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", changed).header("X-SePay-Timestamp", timestamp))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("PAYMENT_UNAUTHENTICATED"));
        verifyNoInteractions(sessions);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CapturedClock {
        @Bean @Primary Clock capturedClock() {
            return Clock.fixed(Instant.ofEpochSecond(Long.parseLong(requiredEnvironment("SEPAY_CAPTURE_TIMESTAMP"))), ZoneOffset.UTC);
        }
    }

    @Test
    void acceptsCapturedBodyWithItsOriginalSignatureHeaders() {
        assertThat(verifier.verify(body, signature, timestamp))
                .as("The original captured body and headers must verify with the matching secret")
                .isTrue();
    }

    @Test
    void rejectsOneByteMutationWithTheOriginalSignatureHeaders() {
        // Do not let an invalid baseline make the tampering assertion pass vacuously.
        assertThat(verifier.verify(body, signature, timestamp))
                .as("Verify the original capture before checking tampering")
                .isTrue();
        byte[] changed = body.clone();
        changed[0] ^= 1;

        assertThat(verifier.verify(changed, signature, timestamp))
                .as("Changing one body byte must invalidate the original signature")
                .isFalse();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        // Assert only presence: never include the value of a credential in test reports.
        assertThat(value != null && !value.isBlank())
                .as("Set %s outside Git before enabling captured-delivery tests", name)
                .isTrue();
        return value;
    }
}
