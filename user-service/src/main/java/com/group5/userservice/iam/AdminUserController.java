package com.group5.userservice.iam;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.userservice.iam.dto.AdminCreateUserRequest;
import com.group5.userservice.iam.dto.AdminPasswordRequest;
import com.group5.userservice.iam.dto.AssignRolesRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AdminUserService users;

    /** Admin tạo user cùng bộ role được chọn từ danh sách cố định. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.CREATE')")
    public ResponseEntity<ApiResponse<UserResponse>> create(@Valid @RequestBody AdminCreateUserRequest request, CurrentUser actor) {
        UserResponse user = users.create(request, actor.id());
        return ResponseEntity.created(URI.create("/api/v1/users/" + user.id())).body(ApiResponse.of(user));
    }

    /** Admin hoặc Support Staff cập nhật whitelist thông tin profile. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('USER.UPDATE')")
    public ApiResponse<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateProfileRequest request, CurrentUser actor) {
        return ApiResponse.of(users.update(id, request, actor.id()));
    }

    /** Admin xóa user theo policy; phản hồi 204 không có body. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, CurrentUser actor) {
        users.delete(id, actor.id());
        return ResponseEntity.noContent().build();
    }

    /** Admin đặt lại mật khẩu theo policy hiện hành của Keycloak. */
    @PutMapping("/{id}/password")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.CHANGE_PASSWORD')")
    public ResponseEntity<Void> password(@PathVariable UUID id, @Valid @RequestBody AdminPasswordRequest request, CurrentUser actor) {
        users.changePassword(id, request.newPassword(), actor.id());
        return ResponseEntity.noContent().build();
    }

    /** Admin thay bộ role nghiệp vụ của tài khoản. */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.UPDATE')")
    public ApiResponse<UserResponse> roles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request, CurrentUser actor) {
        return ApiResponse.of(users.assignRoles(id, request.roleCodes(), actor.id()));
    }

    /** Admin hoặc Support Staff mở khóa tài khoản đang bị brute-force detector khóa. */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('USER.UPDATE')")
    public ResponseEntity<Void> unlock(@PathVariable UUID id, CurrentUser actor) {
        users.unlock(id, actor.id());
        return ResponseEntity.noContent().build();
    }
}
