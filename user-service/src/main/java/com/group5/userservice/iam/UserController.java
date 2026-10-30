package com.group5.userservice.iam;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getMe(CurrentUser currentUser) {
        return ApiResponse.of(userService.getOwnProfile(currentUser.id()));
    }

    @PutMapping("/me")
    public ApiResponse<UserResponse> updateMe(
            CurrentUser currentUser,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.of(userService.updateOwnProfile(currentUser.id(), request));
    }

    @GetMapping("/{id}")
    //#currentUser.id() == #id so hai giá trị UUID với nhau.
    //Đừng so với authentication.name, vì đó là chuỗi nên luôn sai.
    @PreAuthorize("hasAuthority('USER.VIEW') or #currentUser.id() == #id")
    public ApiResponse<UserResponse> getUser(@PathVariable UUID id, CurrentUser currentUser) {
        return ApiResponse.of(userService.getUser(id));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER.LIST')")
    public ApiResponse<List<UserResponse>> listUsers(@Valid UserListRequest request) {
        Page<UserResponse> page = userService.listUsers(request);
        return ApiResponse.page(page.getContent(), new PageInfo(page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages()));
    }
}
