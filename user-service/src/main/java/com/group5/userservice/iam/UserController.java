package com.group5.userservice.iam;

import com.group5.common_security.CurrentUser;
import com.group5.userservice.iam.dto.PageResponse;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserListRequest;
import com.group5.userservice.iam.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponse getMe(CurrentUser currentUser) {
        return userService.getOwnProfile(currentUser.id());
    }

    @PutMapping("/me")
    public UserResponse updateMe(
            CurrentUser currentUser,
            @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateOwnProfile(currentUser.id(), request);
    }

    @GetMapping("/{id}")
    //#currentUser.id() == #id so hai giá trị UUID với nhau.
    //Đừng so với authentication.name, vì đó là chuỗi nên luôn sai.
    @PreAuthorize("hasAuthority('USER.VIEW') or #currentUser.id() == #id")
    public UserResponse getUser(@PathVariable UUID id, CurrentUser currentUser) {
        return userService.getUser(id);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER.LIST')")
    public PageResponse<UserResponse> listUsers(@Valid UserListRequest request) {
        return userService.listUsers(request);
    }
}
