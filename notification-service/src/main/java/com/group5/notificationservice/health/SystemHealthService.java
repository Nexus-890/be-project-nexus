package com.group5.notificationservice.health;

import com.group5.notificationservice.health.dto.BrokerHealthResponse;
import com.group5.notificationservice.health.dto.BrokerStatusResponse;
import com.group5.notificationservice.health.dto.SystemHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Ghép hai nguồn dữ liệu: trạng thái service hỏi trực tiếp qua {@link ServiceHealthChecker}, và trạng thái
 * broker do {@link BrokerHealthJob} lưu trong bảng broker_health. Controller không đọc repository trực tiếp.
 */
@Service
@RequiredArgsConstructor
public class SystemHealthService {

    private final ServiceHealthChecker serviceHealthChecker;
    private final BrokerHealthRepository brokerHealthRepository;

    public SystemHealthResponse systemHealth() {
        List<BrokerStatusResponse> brokers = brokerHealthRepository.findAllByOrderByNameAsc().stream()
                .map(BrokerStatusResponse::from)
                .toList();
        return new SystemHealthResponse(serviceHealthChecker.checkAll(), brokers);
    }

    public List<BrokerHealthResponse> brokers() {
        return brokerHealthRepository.findAllByOrderByNameAsc().stream()
                .map(BrokerHealthResponse::from)
                .toList();
    }
}
