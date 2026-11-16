package com.group5.ratingservice.reputation.controller;

import com.group5.ratingservice.reputation.dto.AdjustmentResult;
import com.group5.ratingservice.reputation.dto.ReputationSnapshot;
import com.group5.ratingservice.reputation.service.ReputationService;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.reputation.dto.AdjustmentRequest;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reputations")
public class ReputationController {
    private final ReputationService reputations;

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','SELLER','BUYER','SUPPORT_STAFF') and hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<ReputationSnapshot> get(@PathVariable UUID userId) {
        return ApiResponse.of(reputations.get(userId));
    }

    @PostMapping("/{userId}/adjustments")
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USER.REPUTATION.ADJUST')")
    public ResponseEntity<ApiResponse<AdjustmentResult>> adjust(
            @PathVariable UUID userId,
            @Valid @RequestBody AdjustmentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Pattern(regexp = "[!-~]{1,128}") String idempotencyKey,
            CurrentUser user) {
        if (user == null) {
            throw new DomainException(RatingErrorCode.UNAUTHENTICATED);
        }
        AdjustmentResult result = reputations.adjust(
                userId, request.delta(), request.reason().strip(), user.id(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.of(result));
    }
}
