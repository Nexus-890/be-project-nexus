package com.group5.userservice.seller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sellers")
@PreAuthorize("hasRole('SELLER')")
@RequiredArgsConstructor
public class SellerController {
    private final SellerService sellers;

    @GetMapping("/me")
    public ApiResponse<SellerProfileResponse> getMe(CurrentUser currentUser) {
        return ApiResponse.of(sellers.getMe(currentUser));
    }

    @PutMapping("/me")
    public ApiResponse<SellerProfileResponse> updateMe(CurrentUser currentUser,
            @Valid @RequestBody SellerProfileRequest request) {
        return ApiResponse.of(sellers.updateMe(currentUser, request));
    }
}
