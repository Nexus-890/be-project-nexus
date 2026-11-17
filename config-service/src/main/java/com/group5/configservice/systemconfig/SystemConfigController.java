package com.group5.configservice.systemconfig;

import com.group5.common_core.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/system/configs")
public class SystemConfigController {
    private final SystemConfigService service;

    /** Nhận service; controller chỉ xử lý HTTP và envelope chuẩn. */
    public SystemConfigController(SystemConfigService service) { this.service = service; }

    /** Chỉ Admin có privilege xem cấu hình mới đọc được danh sách đầy đủ có page. */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('SYSTEM.CONFIG.VIEW')")
    public ApiResponse<List<SystemConfigService.ConfigResponse>> list() { return ApiResponse.list(service.list()); }

    /** Dùng subject JWT làm actor, không cho người gọi giả mạo operator trong JSON. */
    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('SYSTEM.CONFIG.UPDATE')")
    public ApiResponse<SystemConfigService.UpdatedResponse> update(@PathVariable String key,
            @RequestBody UpdateRequest request, @AuthenticationPrincipal Jwt principal) {
        return ApiResponse.of(service.update(key, request.value(), UUID.fromString(principal.getSubject()),
                principal.getTokenValue()));
    }

    public record UpdateRequest(Object value) { }
}
