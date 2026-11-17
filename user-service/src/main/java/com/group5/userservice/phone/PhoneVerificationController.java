package com.group5.userservice.phone;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.userservice.phone.dto.ConfirmPhoneRequest;
import com.group5.userservice.phone.dto.PhoneCodeSentResponse;
import com.group5.userservice.phone.dto.PhoneVerifiedResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me/phone/verification")
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationService verification;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<PhoneCodeSentResponse> send(CurrentUser currentUser) {
        return ApiResponse.of(verification.send(currentUser.id()));
    }

    @PostMapping("/confirm")
    public ApiResponse<PhoneVerifiedResponse> confirm(CurrentUser currentUser,
                                                      @Valid @RequestBody ConfirmPhoneRequest request) {
        return ApiResponse.of(verification.confirm(currentUser.id(), request.code()));
    }
}
