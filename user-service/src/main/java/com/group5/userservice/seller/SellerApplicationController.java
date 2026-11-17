package com.group5.userservice.seller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sellers/applications")
@RequiredArgsConstructor
public class SellerApplicationController {
    private final SellerApplicationService applications;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<SellerApplicationResponse> apply(CurrentUser currentUser,
            @Valid @RequestBody SellerApplicationRequest request) {
        return ApiResponse.of(applications.apply(currentUser, request));
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SellerApplicationResponse> mine(CurrentUser currentUser) {
        return ApiResponse.of(applications.mine(currentUser).orElse(null));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<SellerApplicationResponse>> list(@RequestParam(required = false) SellerStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        Page<SellerApplicationResponse> result = applications.list(status, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping("/{userId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SellerApplicationResponse> approve(@PathVariable UUID userId, CurrentUser currentUser) {
        return ApiResponse.of(applications.approve(userId, currentUser.id()));
    }

    @PostMapping("/{userId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SellerApplicationResponse> reject(@PathVariable UUID userId, CurrentUser currentUser,
            @Valid @RequestBody RejectSellerApplicationRequest request) {
        return ApiResponse.of(applications.reject(userId, request.reason(), currentUser.id()));
    }
}
