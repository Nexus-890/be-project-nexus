package com.group5.paymentservice.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group5.common_events.autoconfigure.EventsAutoConfiguration;
import com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import com.group5.paymentservice.payment.controller.SepayWebhookController;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.service.PaymentMatcher;
import com.group5.paymentservice.shared.config.SepayProperties;
import com.group5.paymentservice.shared.config.SepayWebhookConfiguration;
import com.group5.paymentservice.shared.config.SepayWebhookProperties;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SepayWebhookControllerTest.TestApplication.class,
        initializers = SepayWebhookControllerTest.PaymentYaml.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.payment.sepay.bank-account=0000000002", "nexus.payment.sepay.account-name=NEXUS TEST",
        "SEPAY_WEBHOOK_SECRET=unit-test-only-webhook-secret"
})
class SepayWebhookControllerTest {
    static final String SECRET = "unit-test-only-webhook-secret";
    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    static final String PATH = "/api/v1/webhooks/sepay";
    @Autowired WebApplicationContext context;
    @Autowired PaymentSessionRepository sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(sessions);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @Test
    void signedDeliveryWithoutBearerMatchesAndReturnsOnlyProviderAcknowledgement() throws Exception {
        PaymentSession session = new PaymentSession();
        session.setMemoCode("NX20261105001");
        when(sessions.findByMemoCode(session.getMemoCode())).thenReturn(Optional.of(session));
        byte[] body = bytes("{\"id\":1,\"code\":\" nx20261105001 \",\"transferAmount\":350000,\"providerAddition\":true}");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", sign(body, NOW)).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                .andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"))
                .andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.code").doesNotExist());
        verify(sessions).findByMemoCode("NX20261105001");
        verifyNoMoreInteractions(sessions);
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PENDING);
        assertThat(session.getPaidAt()).isNull();
    }

    @Test
    void unmatchedMemoAcknowledgesAndLogsExactlyOneSafeWarning() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SepayWebhookController.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        byte[] body = bytes("{\"code\":\" nxunknown\\r\\n \",\"description\":\"PRIVATE-BODY-MARKER\"}");
        String signature = sign(body, NOW);
        try {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-SePay-Signature", signature).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                    .andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains("NXUNKNOWN")
                    .doesNotContain(signature, "PRIVATE-BODY-MARKER", "\r", "\n");
            verify(sessions).findByMemoCode("NXUNKNOWN");
            verifyNoMoreInteractions(sessions);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":\"NXKNOWN\"}", "{", "null", ""})
    void oneCharacterSignatureMutationReturnsPayment401BeforeParsingOrLookup(String payload) throws Exception {
        byte[] body = bytes(payload);
        String signature = sign(body, NOW);
        String changed = signature.substring(0, signature.length() - 1)
                + (signature.endsWith("0") ? "1" : "0");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", changed).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail").value("The webhook signature is invalid"))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        verifyNoInteractions(sessions);
    }

    @Test
    void missingHeadersOrBodyAndStaleSignatureAre401WithoutDatabaseAccess() throws Exception {
        byte[] body = bytes("{}");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", sign(body, NOW))).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("X-SePay-Signature", sign(body, NOW)).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                .andExpect(status().isUnauthorized());
        Instant stale = NOW.minusSeconds(301);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", sign(body, stale)).header("X-SePay-Timestamp", stale.getEpochSecond()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{\"id\":{}}", "{} {}"})
    void authenticatedMalformedJsonReturnsNamedPayment400WithoutLookup(String payload) throws Exception {
        byte[] body = bytes(payload);
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", sign(body, NOW)).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_WEBHOOK_REQUEST"));
        verifyNoInteractions(sessions);
    }

    @Test
    void emptyMemoAcknowledgesAndOtherPaymentEndpointsStayProtected() throws Exception {
        byte[] body = bytes("{}");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-SePay-Signature", sign(body, NOW)).header("X-SePay-Timestamp", NOW.getEpochSecond()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/extra")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
    }

    static byte[] bytes(String body) { return body.getBytes(StandardCharsets.UTF_8); }

    static String sign(byte[] body, Instant signedAt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(Long.toString(signedAt.getEpochSecond()).getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) '.');
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    static class PaymentYaml implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("../config-service/src/main/resources/config/payment-service.yml"));
            java.util.Properties security = new java.util.Properties();
            yaml.getObject().forEach((key, value) -> {
                if (key.toString().startsWith("nexus.security.") || key.toString().startsWith("nexus.payment.sepay.")) {
                    security.put(key, value);
                }
            });
            context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("payment-config-yaml", security));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class,
            KafkaAutoConfiguration.class, EventsAutoConfiguration.class,
            IdempotentConsumerAutoConfiguration.class, OutboxAutoConfiguration.class})
    @EnableConfigurationProperties({SepayProperties.class, SepayWebhookProperties.class})
    @Import({SepayWebhookController.class, PaymentMatcher.class, SepayWebhookConfiguration.class})
    static class TestApplication {
        @Bean PaymentSessionRepository sessions() { return mock(PaymentSessionRepository.class); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean JwtDecoder jwtDecoder() { return token -> { throw new BadJwtException("No bearer expected for this webhook"); }; }
    }
}
