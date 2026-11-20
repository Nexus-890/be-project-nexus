package com.group5.shipmentservice.shipping.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.dto.response.CarrierPerformanceResponse;
import com.group5.shipmentservice.shipping.service.CarrierPerformanceService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = CarrierPerformanceControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false"
})
class CarrierPerformanceControllerTest {
    private static final String PATH = "/api/v1/carriers/performance";
    private static final String FROM = "2026-11-01T00:00:00+07:00";
    private static final String TO = "2026-11-19T00:00:00+07:00";

    @Autowired private WebApplicationContext context;
    @MockitoBean private CarrierPerformanceService performance;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(performance);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "support"})
    void staffReadEachCarrierWithTheWindowTheyAskedFor(String token) throws Exception {
        when(performance.performance(any(), any())).thenReturn(List.of(new CarrierPerformanceResponse("GHN",
                "Giao Hàng Nhanh", 5, 2, 1, 1, 1, new BigDecimal("0.6667"), new BigDecimal("36.0"),
                new BigDecimal("0.5000"))));

        mvc.perform(get(PATH).param("from", FROM).param("to", TO).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].carrier").value("GHN"))
                .andExpect(jsonPath("$.data[0].successRate").value(0.6667))
                .andExpect(jsonPath("$.data[0].averageDeliveryHours").value(36.0))
                .andExpect(jsonPath("$.data[0].inProgress").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        verify(performance).performance(OffsetDateTime.parse(FROM), OffsetDateTime.parse(TO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"buyer", "admin-without-privilege"})
    void buyersAndStaffWithoutTheShipmentPrivilegeAreForbidden(String token) throws Exception {
        mvc.perform(get(PATH).param("from", FROM).param("to", TO).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"));
        verifyNoInteractions(performance);
    }

    @Test
    void aMissingOrMalformedBoundIsABadRequestAndABadWindowKeepsItsCode() throws Exception {
        mvc.perform(get(PATH).param("from", FROM).header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).param("from", "yesterday").param("to", TO).header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(performance);
        when(performance.performance(any(), any())).thenThrow(new DomainException(ShipmentErrorCode.INVALID_PERIOD));
        mvc.perform(get(PATH).param("from", TO).param("to", FROM).header("Authorization", "Bearer admin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHIPMENT_INVALID_PERIOD"));
        mvc.perform(get(PATH).param("from", FROM).param("to", TO))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({CarrierPerformanceController.class, ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        private static final Map<String, List<String>> ROLES = Map.of("admin", List.of("ADMIN"),
                "support", List.of("SUPPORT_STAFF"), "buyer", List.of("BUYER"),
                "admin-without-privilege", List.of("ADMIN"));

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!ROLES.containsKey(token)) throw new BadJwtException("Token kiểm thử không hợp lệ");
                return Jwt.withTokenValue(token).header("alg", "RS256")
                        .subject("11111111-1111-4111-8111-111111111111")
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                        .claim("roles", ROLES.get(token))
                        .claim("privileges", token.equals("admin-without-privilege") ? List.of()
                                : List.of("SHIPPING.VIEW_SHIPMENT"))
                        .build();
            };
        }
    }
}
