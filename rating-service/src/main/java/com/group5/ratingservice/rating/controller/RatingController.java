package com.group5.ratingservice.rating.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.rating.dto.CreateRatingRequest;
import com.group5.ratingservice.rating.dto.RatingResponse;
import com.group5.ratingservice.rating.service.RatingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ratings")
public class RatingController {
    private final RatingService ratings;

    @PostMapping
    @PreAuthorize("hasAnyRole('SELLER','BUYER') and hasAuthority('REPUTATION.RATE')")
    public ResponseEntity<ApiResponse<RatingResponse>> create(
            @Valid @RequestBody CreateRatingRequest request, CurrentUser user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ratings.create(request, user)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SELLER','BUYER','SUPPORT_STAFF') and hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<List<RatingResponse>> list(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = ratings.list(userId, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages()));
    }
}
