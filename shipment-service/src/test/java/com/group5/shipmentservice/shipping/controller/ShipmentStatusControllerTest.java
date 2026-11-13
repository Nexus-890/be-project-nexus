package com.group5.shipmentservice.shipping.controller;

import com.group5.shipmentservice.shipping.dto.response.ShipmentTrackingEntryResponse;
import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = ShipmentStatusControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.security.public-endpoints[0].method=POST",
        "nexus.security.public-endpoints[0].path=/api/v1/webhooks/carriers/**"
})
class ShipmentStatusControllerTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SHIPMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String PATH = "/api/v1/shipments/" + SHIPMENT;
    private static final String WEBHOOK = "/api/v1/webhooks/carriers/ghn";

    @Autowired WebApplicationContext context;
    @MockitoBean ShipmentStatusService statuses;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(statuses);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"support", "admin"})
    void staffUpdateUsesSuccessEnvelopeAndAuthenticatedActor(String token) throws Exception {
        when(statuses.update(any(CurrentUser.class), eq(SHIPMENT), eq(ShipmentStatus.IN_TRANSIT), eq("Carrier confirmed"), eq(false)))
                .thenReturn(response());

        mvc.perform(patch(PATH + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_TRANSIT\",\"reason\":\"Carrier confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.id").value(SHIPMENT.toString()))
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.page").doesNotExist());

        verify(statuses).update(any(CurrentUser.class), eq(SHIPMENT), eq(ShipmentStatus.IN_TRANSIT),
                eq("Carrier confirmed"), eq(false));
    }

    @Test
    void overrideRequiresItsOwnPrivilegeAndSuppliesReason() throws Exception {
        when(statuses.update(any(CurrentUser.class), eq(SHIPMENT), eq(ShipmentStatus.DELIVERED), eq("Proof received"), eq(true)))
                .thenReturn(response());

        mvc.perform(post(PATH + "/override").header("Authorization", "Bearer support")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"reason\":\"Proof received\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(statuses).update(any(CurrentUser.class), eq(SHIPMENT), eq(ShipmentStatus.DELIVERED),
                eq("Proof received"), eq(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"DELIVERED\"}",
            "{\"status\":\"DELIVERED\",\"reason\":\" \"}"})
    void overrideRejectsMissingStatusOrBlankReason(String body) throws Exception {
        mvc.perform(post(PATH + "/override").header("Authorization", "Bearer support")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isNotEmpty());

        verifyNoInteractions(statuses);
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer-with-privileges", "admin-no-privileges"})
    void rolesAndPrivilegesAreBothRequiredForManualWrites(String token) throws Exception {
        mvc.perform(patch(PATH + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        mvc.perform(post(PATH + "/override").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"reason\":\"Proof received\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));

        verifyNoInteractions(statuses);
    }

    @Test
    void updatePrivilegeDoesNotGrantOverride() throws Exception {
        mvc.perform(post(PATH + "/override").header("Authorization", "Bearer update-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\",\"reason\":\"Proof received\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));

        verifyNoInteractions(statuses);
    }

    @Test
    void missingAuthenticationReturnsProblemDetailsAndBearerChallenge() throws Exception {
        mvc.perform(patch(PATH + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));

        verifyNoInteractions(statuses);
    }

    @Test
    void backwardTransitionReturns409ProblemWithoutSuccessEnvelope() throws Exception {
        when(statuses.update(any(CurrentUser.class), eq(SHIPMENT), eq(ShipmentStatus.IN_TRANSIT), eq(null), eq(false)))
                .thenThrow(new DomainException(ShipmentErrorCode.INVALID_TRANSITION));

        mvc.perform(patch(PATH + "/status").header("Authorization", "Bearer support")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_TRANSIT\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_TRANSITION"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.instance").value(PATH + "/status"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void trackingReturnsHistoryArrayWithSiblingPage() throws Exception {
        var row = new ShipmentTrackingEntryResponse(UUID.randomUUID(), ShipmentStatus.IN_TRANSIT, "Carrier update",
                "CARRIER_WEBHOOK", "event-1", ACTOR, OffsetDateTime.parse("2026-11-11T00:00:00Z"));
        when(statuses.tracking(any(CurrentUser.class), eq(SHIPMENT), eq(1), eq(2)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(1, 2), 3));

        mvc.perform(get(PATH + "/history").header("Authorization", "Bearer buyer")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].source").value("CARRIER_WEBHOOK"))
                .andExpect(jsonPath("$.data[0].status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void carrierCallbackUsesProviderSuccessBodyWithoutBearerOrApplicationEnvelope() throws Exception {
        mvc.perform(post(WEBHOOK).header("X-Carrier-Signature", "provider-credential")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"OrderCode\":\"TRACK-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());

        verify(statuses).webhook(eq("ghn"), any(byte[].class), eq("provider-credential"));
    }

    @Test
    void invalidWebhookSignatureStillUsesNamed401ProblemDetails() throws Exception {
        doThrow(new DomainException(ShipmentErrorCode.INVALID_SIGNATURE))
                .when(statuses).webhook(eq("ghn"), any(byte[].class), eq("bad"));

        mvc.perform(post(WEBHOOK).header("X-Carrier-Signature", "bad")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_SIGNATURE"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value(WEBHOOK))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static ShipmentResponse response() {
        OffsetDateTime changed = OffsetDateTime.parse("2026-11-11T00:00:00Z");
        return new ShipmentResponse(SHIPMENT, UUID.randomUUID(), UUID.randomUUID(), ACTOR, "FAKE", "STANDARD",
                "TRACK-1", ShipmentStatus.IN_TRANSIT, new BigDecimal("30000.00"), Map.of(), changed, changed, 1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class}, excludeName = {
            "com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({ShipmentStatusController.class, CarrierWebhookController.class,
            ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!List.of("support", "admin", "buyer", "buyer-with-privileges", "admin-no-privileges", "update-only")
                        .contains(token)) {
                    throw new BadJwtException("Invalid test token");
                }
                List<String> roles = switch (token) {
                    case "support", "update-only" -> List.of("SUPPORT_STAFF");
                    case "admin", "admin-no-privileges" -> List.of("ADMIN");
                    default -> List.of("BUYER");
                };
                List<String> privileges = switch (token) {
                    case "admin-no-privileges" -> List.of();
                    case "buyer" -> List.of("SHIPPING.VIEW_SHIPMENT");
                    case "update-only" -> List.of("SHIPPING.UPDATE_STATUS");
                    default -> List.of("SHIPPING.UPDATE_STATUS", "SHIPPING.MANUAL_OVERRIDE", "SHIPPING.VIEW_SHIPMENT");
                };
                return Jwt.withTokenValue(token).header("alg", "RS256").subject(ACTOR.toString())
                        .issuedAt(Instant.parse("2026-11-11T00:00:00Z"))
                        .expiresAt(Instant.parse("2026-11-12T00:00:00Z"))
                        .claim("roles", roles).claim("privileges", privileges).build();
            };
        }
    }
}
