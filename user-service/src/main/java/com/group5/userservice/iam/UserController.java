package com.group5.userservice.iam;

import com.group5.common_security.CurrentUser;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
