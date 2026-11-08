package com.group5.notificationservice.health;

import com.group5.common_core.model.ApiResponse;
import com.group5.notificationservice.health.dto.BrokerHealthResponse;
import com.group5.notificationservice.health.dto.SystemHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lỗi 401 và 403 do common-security ({@code SecurityProblemAdvice}) trả về dạng Problem Details
 * với code UNAUTHENTICATED và FORBIDDEN, nên controller không cần mã lỗi NOTIFICATION_* riêng.
 */
@RestController
@RequestMapping("/api/v1/system/health")
@RequiredArgsConstructor
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    /** Trả về một object nên dùng {@code ApiResponse.of}, response không có page. */
    @GetMapping
    @PreAuthorize("hasAuthority('SYSTEM.HEALTH.VIEW')")
    public ApiResponse<SystemHealthResponse> systemHealth() {
        return ApiResponse.of(systemHealthService.systemHealth());
    }

    /**
     * Trả về danh sách nên dùng {@code ApiResponse.list}. Hàm này tự thêm page với number 0,
     * size max(1, n), totalElements n, totalPages 0 khi rỗng và 1 khi có dữ liệu.
     */
    @GetMapping("/brokers")
    @PreAuthorize("hasAuthority('SYSTEM.HEALTH.VIEW')")
    public ApiResponse<List<BrokerHealthResponse>> brokers() {
        return ApiResponse.list(systemHealthService.brokers());
    }
}
