package com.group5.ratingservice.moderation.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.moderation.dto.*;
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
@RequestMapping("/api/v1/ratings")
public class DisputeController {
    private final ModerationService moderation;

    @PostMapping("/{id}/disputes")
    @PreAuthorize("hasAnyRole('BUYER','SELLER') and hasAuthority('REPUTATION.RATE')")
    public ResponseEntity<ApiResponse<DisputeResponse>> create(@PathVariable UUID id,
            @Valid @RequestBody DisputeRequest request, CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(moderation.dispute(id, request.reason(), user.id())));
    }

    @GetMapping("/disputes")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('USER.REPUTATION.VIEW')")
    public ApiResponse<List<DisputeResponse>> list(@RequestParam(required = false) DisputeStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = moderation.disputes(status, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    @PatchMapping("/disputes/{id}")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.REPUTATION.ADJUST')")
    public ApiResponse<DisputeResponse> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveDisputeRequest request, CurrentUser user) {
        return ApiResponse.of(moderation.resolve(id, request.status(), request.reason(), user.id()));
    }
}
