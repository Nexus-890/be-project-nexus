package com.group5.ratingservice.eligibility.controller;

import com.group5.ratingservice.eligibility.dto.EligibilityResponse;
import com.group5.ratingservice.eligibility.enums.EligibilityAction;

import com.group5.common_core.model.ApiResponse;
import com.group5.ratingservice.reputation.policy.ReputationPolicy;
import com.group5.ratingservice.reputation.service.ReputationService;
import com.group5.ratingservice.moderation.service.ModerationService;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/eligibility")
public class EligibilityController {
    private final ReputationService reputations;
    private final ReputationPolicy policy;
    private final ModerationService moderation;

    @GetMapping("/{userId}")
    @PreAuthorize("@eligibilityAuthorization.allowed(authentication)")
    public ApiResponse<EligibilityResponse> get(
            @PathVariable UUID userId, @RequestParam EligibilityAction action) {
        var reputation = reputations.eligibility(userId);
        int minimum = action == EligibilityAction.BID ? policy.minimumToBid() : policy.minimumToSell();
        OffsetDateTime restrictedUntil = moderation.restrictedUntil(userId, action);
        boolean eligible = restrictedUntil == null && reputation.score() >= minimum;
        String reason = restrictedUntil != null ? action + " is restricted until " + restrictedUntil : eligible
                ? "Reputation score meets the " + action + " minimum"
                : "Reputation score " + reputation.score() + " is below " + minimum + " required for " + action;
        return ApiResponse.of(new EligibilityResponse(
                userId, action, eligible, reputation.score(), reputation.trustLevel(), minimum,
                reason, reputation.policyVersion(), restrictedUntil));
    }
}
