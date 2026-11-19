package com.group5.ratingservice.moderation.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.moderation.dto.RestrictionRequest;
import com.group5.ratingservice.moderation.dto.RestrictionResponse;
import com.group5.ratingservice.moderation.service.ModerationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reputations/{userId}/restrictions")
public class RestrictionController {
    private final ModerationService moderation;

    @GetMapping
    @PreAuthorize("(hasRole('ADMIN') and hasAuthority('USER.REPUTATION.VIEW')) or (hasAnyRole('BUYER','SELLER') and hasAuthority('REPUTATION.VIEW') and #userId.toString() == authentication.name)")
    public ApiResponse<List<RestrictionResponse>> list(@PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = moderation.restrictions(userId, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.REPUTATION.ADJUST')")
    public ResponseEntity<ApiResponse<RestrictionResponse>> create(@PathVariable UUID userId,
            @Valid @RequestBody RestrictionRequest request, CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(moderation.restrict(userId, request, user.id())));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.REPUTATION.ADJUST')")
    public ApiResponse<RestrictionResponse> revoke(@PathVariable UUID userId, @PathVariable UUID id,
            @RequestParam @NotBlank @Size(max = 1000) String reason, CurrentUser user) {
        return ApiResponse.of(moderation.revoke(userId, id, reason, user.id()));
    }
}
