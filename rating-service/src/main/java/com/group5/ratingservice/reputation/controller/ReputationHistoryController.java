package com.group5.ratingservice.reputation.controller;

import com.group5.ratingservice.reputation.dto.ReputationHistoryItem;
import com.group5.ratingservice.reputation.service.ReputationService;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reputations")
public class ReputationHistoryController {
    private final ReputationService reputations;

    @GetMapping("/{userId}/history")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('USER.REPUTATION.VIEW')")
    public ApiResponse<List<ReputationHistoryItem>> history(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = reputations.history(userId, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }
}
