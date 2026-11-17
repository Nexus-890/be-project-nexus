package com.group5.userservice.iam;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Validated
public class RoleController {
    private final RoleService roles;

    /** Trả một trang role cố định, cho phép tìm kiếm và sắp xếp theo tên. */
    @GetMapping("/roles")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('ROLE.LIST')")
    public ApiResponse<List<RoleService.RoleResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "name,asc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (!sort.equals("name,asc") && !sort.equals("name,desc")) throw new DomainException(UserErrorCode.USER_ROLE_INVALID);
        List<RoleService.RoleResponse> found = new ArrayList<>(roles.list(q));
        if (sort.endsWith("desc")) Collections.reverse(found);
        int from = (int) Math.min((long) page * size, found.size());
        return ApiResponse.page(found.subList(from, Math.min(from + size, found.size())),
                new PageInfo(page, size, found.size(), (found.size() + size - 1) / size));
    }

    /** Trả thông tin role và bộ quyền đọc trực tiếp từ Keycloak. */
    @GetMapping("/roles/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('ROLE.VIEW')")
    public ApiResponse<RoleService.RoleResponse> get(@PathVariable UUID id) { return ApiResponse.of(roles.get(id)); }

    /** Trả danh sách quyền hiện hành dưới complete-list envelope có page. */
    @GetMapping("/permissions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('ROLE.VIEW')")
    public ApiResponse<List<RoleService.PermissionResponse>> permissions() { return ApiResponse.list(roles.permissions()); }
}
