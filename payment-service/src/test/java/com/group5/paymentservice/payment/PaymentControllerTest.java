package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.controller.PaymentController;
import com.group5.paymentservice.payment.entity.PaymentInstruction;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.common_events.autoconfigure.EventsAutoConfiguration;
import com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration;
import com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration;
import com.group5.paymentservice.payment.entity.PaymentTransaction;
import com.group5.paymentservice.payment.enums.PaymentInstructionStatus;
import com.group5.paymentservice.payment.enums.PaymentSessionStatus;
import com.group5.paymentservice.payment.provider.PaymentProviderRegistry;
import com.group5.paymentservice.payment.provider.sepay.SepayPaymentProvider;
import com.group5.paymentservice.payment.repository.PaymentInstructionRepository;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.repository.PaymentTransactionRepository;
import com.group5.paymentservice.payment.service.PaymentQueryService;
import com.group5.paymentservice.payment.service.PaymentService;
import com.group5.paymentservice.shared.config.PaymentProperties;
import com.group5.paymentservice.shared.config.SepayProperties;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = PaymentControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.payment.provider=SEPAY", "nexus.payment.sepay.bank-id=970415",
        "nexus.payment.sepay.bank-account=0000000002", "nexus.payment.sepay.account-name=NEXUS TEST",
        "nexus.payment.sepay.vietqr-template=compact",
        "nexus.payment.sepay.transfer-content-prefix=SEVQR"
})
class PaymentControllerTest {
    private static final UUID BUYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID GROUP = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    @Autowired private WebApplicationContext context;
    @Autowired private PaymentSessionRepository sessions;
    @Autowired private PaymentInstructionRepository instructions;
    @Autowired private Clock clock;
    private MockMvc mvc;
    private PaymentSession session;
    private List<PaymentInstruction> plan;

    @BeforeEach
    void setUp() {
        reset(sessions, instructions, clock);
        when(clock.instant()).thenReturn(NOW);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        session = new PaymentSession();
        session.setId(UUID.randomUUID());
        session.setOrderGroupId(GROUP);
        session.setBuyerId(BUYER);
        session.setTotalAmount(new BigDecimal("350000.00"));
        session.setMemoCode("NX20261105001");
        session.setDueAt(OffsetDateTime.ofInstant(NOW.plusSeconds(900), ZoneOffset.UTC));
        plan = List.of(instruction("200000.00"), instruction("150000.00"));
        when(sessions.findByOrderGroupId(GROUP)).thenReturn(Optional.of(session));
        when(instructions.findBySessionIdOrderByOrderId(session.getId())).thenReturn(plan);
    }

    @Test
    void twoSellerOrdersReturn201AndRepeatedCallsPerformOnlyReads() throws Exception {
        String body = "{\"orderGroupId\":\"" + GROUP + "\",\"amount\":1,\"memoCode\":\"FORGED\"}";
        String first = mvc.perform(post("/api/v1/payments").header("Authorization", "Bearer buyer")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.data.data").doesNotExist())
                .andExpect(jsonPath("$.data.sessionId").value(session.getId().toString()))
                .andExpect(jsonPath("$.data.amount").isNumber())
                .andExpect(jsonPath("$.data.amount").value(350000))
                .andExpect(jsonPath("$.data.bankAccount").value("0000000002"))
                .andExpect(jsonPath("$.data.memoCode").value("NX20261105001"))
                .andExpect(jsonPath("$.data.transferContent").value("SEVQR NX20261105001"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-05T10:15:00Z"))
                .andExpect(jsonPath("$.data.serverNow").value("2026-11-05T10:00:00Z"))
                .andExpect(jsonPath("$.data.qrImageUrl").value(org.hamcrest.Matchers.containsString(
                        "amount=350000&des=SEVQR%20NX20261105001")))
                .andReturn().getResponse().getContentAsString();
        String second = call("buyer", request()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
        verify(sessions, times(2)).findByOrderGroupId(GROUP);
        verify(instructions, times(2)).findBySessionIdOrderByOrderId(session.getId());
        verifyNoMoreInteractions(sessions, instructions);
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PENDING);
    }

    @Test
    void repeatedReadRefreshesServerTimeWithoutExtendingDeadlineOrReplacingMemo() throws Exception {
        String first = call("buyer", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.serverNow").value("2026-11-05T10:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        when(clock.instant()).thenReturn(NOW.plusSeconds(120));
        String second = call("buyer", request()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.serverNow").value("2026-11-05T10:02:00Z"))
                .andExpect(jsonPath("$.data.memoCode").value("NX20261105001"))
                .andExpect(jsonPath("$.data.transferContent").value("SEVQR NX20261105001"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-05T10:15:00Z"))
                .andReturn().getResponse().getContentAsString();
        assertThat(second.replace("2026-11-05T10:02:00Z", "2026-11-05T10:00:00Z"))
                .isEqualTo(first);
        verify(sessions, times(2)).findByOrderGroupId(GROUP);
        verify(instructions, times(2)).findBySessionIdOrderByOrderId(session.getId());
        verifyNoMoreInteractions(sessions, instructions);
    }

    @Test
    void anotherBuyerGets403BeforeReadingInstructionsEvenIfSessionIsCanceled() throws Exception {
        session.setStatus(PaymentSessionStatus.CANCELED);
        call("other", request()).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_SESSION_ACCESS_DENIED"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(instructions);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSessionStatus.class, names = {"PAID", "CANCELED", "EXPIRED"})
    void terminalSessionsReturn409(PaymentSessionStatus status) throws Exception {
        session.setStatus(status);
        call("buyer", request()).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PAYMENT_SESSION_NOT_PAYABLE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(instructions);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void deadlineAtOrBeforeNowReturns409WithoutChangingStatus(long seconds) throws Exception {
        session.setDueAt(OffsetDateTime.ofInstant(NOW.plusSeconds(seconds), ZoneOffset.UTC));
        call("buyer", request()).andExpect(status().isConflict());
        assertThat(session.getStatus()).isEqualTo(PaymentSessionStatus.PENDING);
        verifyNoInteractions(instructions);
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        when(sessions.findByOrderGroupId(GROUP)).thenReturn(Optional.empty());
        call("buyer", request()).andExpect(status().isNotFound());
        verifyNoInteractions(instructions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderGroupId\":null}", "{\"orderGroupId\":\"bad-id\"}", "{"})
    void invalidRequestsReturn400BeforeDatabaseAccess(String body) throws Exception {
        call("buyer", body).andExpect(status().isBadRequest());
        verifyNoInteractions(sessions, instructions);
    }

    @Test
    void missingOrInvalidBearerReturns401AndMissingPrivilegeReturns403() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isUnauthorized());
        call("invalid", request()).andExpect(status().isUnauthorized());
        call("no-privilege", request()).andExpect(status().isForbidden());
        verifyNoInteractions(sessions, instructions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "sum", "buyer", "currency", "deadline", "status", "funded"})
    void inconsistentInstructionsReturn409(String mismatch) throws Exception {
        PaymentInstruction first = plan.getFirst();
        switch (mismatch) {
            case "empty" -> when(instructions.findBySessionIdOrderByOrderId(session.getId())).thenReturn(List.of());
            case "sum" -> first.setAmount(new BigDecimal("199999"));
            case "buyer" -> first.setBuyerId(UUID.randomUUID());
            case "currency" -> first.setCurrency("USD");
            case "deadline" -> first.setDueAt(session.getDueAt().minusSeconds(1));
            case "status" -> first.setStatus(PaymentInstructionStatus.CANCELED);
            case "funded" -> first.setFundedTransaction(new PaymentTransaction());
            default -> throw new IllegalArgumentException(mismatch);
        }
        call("buyer", request()).andExpect(status().isConflict());
    }

    private org.springframework.test.web.servlet.ResultActions call(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/payments").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String request() {
        return "{\"orderGroupId\":\"" + GROUP + "\"}";
    }

    private PaymentInstruction instruction(String amount) {
        PaymentInstruction instruction = new PaymentInstruction();
        instruction.setOrderId(UUID.randomUUID());
        instruction.setSession(session);
        instruction.setBuyerId(BUYER);
        instruction.setAmount(new BigDecimal(amount));
        instruction.setDueAt(session.getDueAt());
        return instruction;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class, FlywayAutoConfiguration.class,
            KafkaAutoConfiguration.class, EventsAutoConfiguration.class,
            IdempotentConsumerAutoConfiguration.class, OutboxAutoConfiguration.class})
    @EnableConfigurationProperties({PaymentProperties.class, SepayProperties.class})
    @Import({PaymentController.class, PaymentService.class, PaymentQueryService.class, PaymentProviderRegistry.class, SepayPaymentProvider.class})
    static class TestApplication {
        @Bean PaymentSessionRepository sessions() { return mock(PaymentSessionRepository.class); }
        @Bean PaymentInstructionRepository instructions() { return mock(PaymentInstructionRepository.class); }
        @Bean PaymentTransactionRepository transactions() { return mock(PaymentTransactionRepository.class); }
        @Bean Clock clock() { return mock(Clock.class); }
        @Bean JwtDecoder jwtDecoder() {
            return token -> {
                if ("invalid".equals(token)) throw new BadJwtException("Invalid test token");
                String subject = "other".equals(token) ? "33333333-3333-4333-8333-333333333333" : BUYER.toString();
                return Jwt.withTokenValue(token).header("alg", "RS256").subject(subject)
                        .claim("roles", List.of("BUYER"))
                        .claim("privileges", "no-privilege".equals(token) ? List.of() : List.of("CHECKOUT.START"))
                        .build();
            };
        }
    }
}
