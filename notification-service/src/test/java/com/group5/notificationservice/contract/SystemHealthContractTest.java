package com.group5.notificationservice.contract;

import com.group5.common_web.exception.GlobalExceptionHandler;
import com.group5.notificationservice.health.HealthStatus;
import com.group5.notificationservice.health.SystemHealthController;
import com.group5.notificationservice.health.SystemHealthService;
import com.group5.notificationservice.health.dto.BrokerHealthResponse;
import com.group5.notificationservice.health.dto.BrokerStatusResponse;
import com.group5.notificationservice.health.dto.ServiceHealthResponse;
import com.group5.notificationservice.health.dto.SystemHealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SystemHealthContractTest {

    private static final Instant CHECKED = Instant.parse("2026-11-04T02:31:00Z");
    private static final Instant FAILED = Instant.parse("2026-11-04T02:25:30Z");
    private static final Instant RECOVERED = Instant.parse("2026-11-04T02:30:45Z");

    private SystemHealthService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(SystemHealthService.class);
        mvc = standaloneSetup(new SystemHealthController(service))
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .build();
    }

    @Test
    void systemHealthReturnsServicesAndBrokersInTheEnvelope() throws Exception {
        when(service.systemHealth()).thenReturn(new SystemHealthResponse(
                List.of(new ServiceHealthResponse("catalog-service", HealthStatus.DOWN),
                        new ServiceHealthResponse("keycloak", HealthStatus.UP)),
                List.of(new BrokerStatusResponse("kafka", HealthStatus.UP, CHECKED))));

        mvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.services[0].name").value("catalog-service"))
                .andExpect(jsonPath("$.data.services[0].status").value("DOWN"))
                .andExpect(jsonPath("$.data.services[1].name").value("keycloak"))
                .andExpect(jsonPath("$.data.brokers[0].name").value("kafka"))
                .andExpect(jsonPath("$.data.brokers[0].status").value("UP"))
                .andExpect(jsonPath("$.data.brokers[0].lastCheckedAt").exists())
                .andExpect(jsonPath("$.data.brokers[0].lastFailureAt").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test
    void brokersAreAListWithPage() throws Exception {
        when(service.brokers()).thenReturn(List.of(
                new BrokerHealthResponse("kafka", HealthStatus.UP, CHECKED, FAILED, RECOVERED)));

        mvc.perform(get("/api/v1/system/health/brokers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].name").value("kafka"))
                .andExpect(jsonPath("$.data[0].status").value("UP"))
                .andExpect(jsonPath("$.data[0].lastCheckedAt").exists())
                .andExpect(jsonPath("$.data[0].lastFailureAt").exists())
                .andExpect(jsonPath("$.data[0].lastRecoveredAt").exists())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void anUnknownBrokerKeepsItsFieldsAsNull() throws Exception {
        when(service.systemHealth()).thenReturn(new SystemHealthResponse(
                List.of(new ServiceHealthResponse("keycloak", HealthStatus.UP)),
                List.of(new BrokerStatusResponse("kafka", HealthStatus.UNKNOWN, null))));
        when(service.brokers()).thenReturn(List.of(
                new BrokerHealthResponse("kafka", HealthStatus.UNKNOWN, null, null, null)));

        mvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brokers[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.brokers[0]", hasKey("lastCheckedAt")));
        mvc.perform(get("/api/v1/system/health/brokers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data[0]", hasKey("lastFailureAt")))
                .andExpect(jsonPath("$.data[0]", hasKey("lastRecoveredAt")));
    }
}
