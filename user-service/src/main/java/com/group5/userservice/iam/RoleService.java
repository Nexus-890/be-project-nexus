package com.group5.userservice.iam;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RoleService {
    private final KeycloakAdminClient keycloak;
    public record PermissionResponse(String code, String name, String description) { }
    public record RoleResponse(String id, String code, String name, String description, List<PermissionResponse> privileges) { }

    /** Đọc role hiện tại từ realm và lọc tên/code không phân biệt hoa thường. */
    public List<RoleResponse> list(String query) {
        String filter = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return keycloak.availableRealmRoles().stream().filter(role -> role.name().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(Comparator.comparing(KeycloakAdminClient.RoleDetails::name)).map(this::response).toList();
    }

    /** Tra realm role nghiệp vụ theo ID do Keycloak cấp. */
    public RoleResponse get(UUID id) { return response(keycloak.realmRoleById(id)); }

    /** Trả toàn bộ client role quyền hiện có của nexus-web theo thứ tự code. */
    public List<PermissionResponse> permissions() {
        return keycloak.permissions().stream().sorted(Comparator.comparing(KeycloakAdminClient.RoleDetails::name))
                .map(RoleService::permission).toList();
    }

    /** Ghép realm role với các client role làm privilege của nó. */
    private RoleResponse response(KeycloakAdminClient.RoleDetails role) {
        return new RoleResponse(role.id(), role.name(), role.name(), role.description(),
                keycloak.rolePermissions(role.name()).stream().sorted(Comparator.comparing(KeycloakAdminClient.RoleDetails::name))
                        .map(RoleService::permission).toList());
    }

    /** Chuyển client role Keycloak sang DTO quyền mà không sử dụng bảng IAM đã retired. */
    private static PermissionResponse permission(KeycloakAdminClient.RoleDetails role) {
        return new PermissionResponse(role.name(), role.name(), role.description());
    }
}
