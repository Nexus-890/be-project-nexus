package com.group5.ratingservice.reputation.controller;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.reputation.dto.MyReputation;
import com.group5.ratingservice.reputation.dto.PenaltyItem;
import com.group5.ratingservice.reputation.service.ReputationService;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users/me")
public class MyReputationController {
    private final ReputationService reputations;

    @GetMapping("/reputation")
    @PreAuthorize("hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<MyReputation> reputation(CurrentUser user) {
        return ApiResponse.of(reputations.mine(owner(user)));
    }

    @GetMapping("/penalties")
    @PreAuthorize("hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<List<PenaltyItem>> penalties(
            CurrentUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        var result = reputations.penalties(owner(user), page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }

    private static UUID owner(CurrentUser user) {
        if (user == null) {
            throw new DomainException(RatingErrorCode.UNAUTHENTICATED);
        }
        return user.id();
    }
}
