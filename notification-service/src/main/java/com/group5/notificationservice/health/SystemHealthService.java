package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.BrokerHealthResponse;
import com.group5.notificationservice.health.dto.BrokerStatusResponse;
import com.group5.notificationservice.health.dto.SystemHealthResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Ghép hai nguồn dữ liệu: trạng thái service hỏi trực tiếp qua {@link ServiceHealthChecker}, và trạng thái
 * broker do {@link BrokerHealthJob} lưu trong Redis. Controller không đọc Redis trực tiếp.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final ServiceHealthChecker serviceHealthChecker;
    private final HealthStateStore store;

    public SystemHealthResponse systemHealth() {
        List<BrokerStatusResponse> brokers = brokerStates().stream().map(BrokerStatusResponse::from).toList();
        return new SystemHealthResponse(serviceHealthChecker.checkAll(), brokers);
    }

    public List<BrokerHealthResponse> brokers() {
        return brokerStates().stream().map(BrokerHealthResponse::from).toList();
    }

    private List<BrokerHealth> brokerStates() {
        return BrokerHealthJob.BROKERS.stream().map(this::state).toList();
    }

    // REDIS LỖI -> BROKER UNKNOWN (DEGRADED), RESPONSE VẪN 200 VÀ VẪN CÓ PHẦN SERVICES
    private BrokerHealth state(String broker) {
        try {
            return store.find(broker).orElseGet(() -> BrokerHealth.unknown(broker));
        } catch (DataAccessException error) {
            log.warn("Health state store unreachable: broker={} error={}", broker, error.getClass().getSimpleName());
            return BrokerHealth.unknown(broker);
        }
    }
}
