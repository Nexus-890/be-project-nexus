package com.group5.shipmentservice.shipping.controller;

import com.group5.common_security.SecurityProblemAdvice;
import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.shipmentservice.shared.config.ShippingConfiguration;
import com.group5.shipmentservice.shared.exception.ShipmentExceptionHandler;
import com.group5.shipmentservice.shared.exception.ShipmentSecurityProblemAdvice;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.CarrierService;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = CarrierControllerTest.TestApplication.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "eureka.client.enabled=false",
        "management.tracing.export.zipkin.enabled=false",
        "nexus.shipping.carrier=FAKE"
})
class CarrierControllerTest {
    private static final String PATH = "/api/v1/carriers";

    @Autowired
    private WebApplicationContext context;

    @MockitoSpyBean
    private CarrierService carrierService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(carrierService);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void returnsConfiguredCarriersWithEnvelopeAndPage() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("FAKE"))
                .andExpect(jsonPath("$.data[0].name").value("Simulated carrier"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[0].available").value(true))
                .andExpect(jsonPath("$.data[0].token").doesNotExist())
                .andExpect(jsonPath("$.data[0].shopId").doesNotExist())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void returnsEmptyLogicalPageWhenNoCarrierIsAvailable() throws Exception {
        doReturn(List.of()).when(carrierService).getCarriers();

        mvc.perform(get(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test
    void missingTokenReturnsShipmentProblemAndBearerChallenge() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.type").value("urn:problem:unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(carrierService);
    }

    @Test
    void invalidTokenReturnsShipmentProblem() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("SHIPMENT_UNAUTHENTICATED"));
        verifyNoInteractions(carrierService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"without-privilege", "admin"})
    void missingPrivilegeIsForbiddenEvenForAdmin(String token) throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.instance").value(PATH))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(carrierService);
    }

    @Test
    void unsupportedMethodKeepsAllowHeaderAndShipmentCode() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedResponseFormatReturnsShipmentProblem() throws Exception {
        mvc.perform(get(PATH).header("Authorization", "Bearer buyer").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_ACCEPTABLE"));
    }

    @Test
    void unknownPathReturnsNotFoundInsteadOfInternalError() throws Exception {
        mvc.perform(get(PATH + "/khong-ton-tai").header("Authorization", "Bearer buyer"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_NOT_FOUND"));
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetail() throws Exception {
        doThrow(new IllegalStateException("du-lieu-noi-bo-chi-de-test"))
                .when(carrierService).getCarriers();

        mvc.perform(get(PATH).header("Authorization", "Bearer buyer"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SHIPMENT_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(content().string(not(containsString("du-lieu-noi-bo-chi-de-test"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void shipmentAdviceReplacesSharedFallbackBeans() {
        assertThat(context.getBeansOfType(SecurityProblemAdvice.class))
                .hasSize(1).allSatisfy((name, advice) ->
                        assertThat(advice).isInstanceOf(ShipmentSecurityProblemAdvice.class));
        assertThat(context.getBeansOfType(GlobalExceptionHandler.class))
                .hasSize(1).allSatisfy((name, advice) ->
                        assertThat(advice).isInstanceOf(ShipmentExceptionHandler.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    }, excludeName = {"com.group5.common_outbox.autoconfigure.IdempotentConsumerAutoConfiguration",
            "com.group5.common_outbox.autoconfigure.OutboxAutoConfiguration"})
    @Import({ShippingConfiguration.class, CarrierRegistry.class, CarrierExecutor.class,
            CarrierService.class, CarrierController.class,
            ShipmentSecurityProblemAdvice.class, ShipmentExceptionHandler.class})
    static class TestApplication {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                if (!List.of("buyer", "without-privilege", "admin").contains(token)) {
                    throw new BadJwtException("Token kiểm thử không hợp lệ");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("11111111-1111-4111-8111-111111111111")
                        .issuedAt(Instant.parse("2026-11-09T00:00:00Z"))
                        .expiresAt(Instant.parse("2026-11-10T00:00:00Z"))
                        .claim("roles", token.equals("admin") ? List.of("ADMIN") : List.of("BUYER"))
                        .claim("privileges", token.equals("buyer") ? List.of("SHIPPING.QUOTE") : List.of())
                        .build();
            };
        }
    }
}
