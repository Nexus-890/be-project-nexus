package com.group5.userservice.iam;

import com.group5.common_core.model.ApiResponse;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.group5.userservice.iam.dto.VerificationEmailRequest;
import com.group5.userservice.iam.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final EmailVerificationService emailVerification;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(userService.register(request)));
    }

    @PostMapping("/verification-email")
    public ResponseEntity<Void> resendVerificationEmail(@Valid @RequestBody VerificationEmailRequest request) {
        emailVerification.resend(request.email());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
