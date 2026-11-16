package com.group5.ratingservice.rating;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.rating.dto.CreateRatingRequest;
import com.group5.ratingservice.rating.dto.RatingResponse;
import com.group5.ratingservice.rating.dto.ReputationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RatingController {
    private final RatingService ratings;

    @PostMapping("/ratings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('REPUTATION.RATE')")
    public ApiResponse<RatingResponse> create(@Valid @RequestBody CreateRatingRequest request, CurrentUser user) {
        return ApiResponse.of(ratings.create(request, user));
    }

    @GetMapping("/ratings")
    @PreAuthorize("hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<List<RatingResponse>> list(@RequestParam UUID userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        var result = ratings.list(userId, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/reputations/{userId}")
    @PreAuthorize("hasAuthority('REPUTATION.VIEW')")
    public ApiResponse<ReputationResponse> reputation(@PathVariable UUID userId) {
        return ApiResponse.of(ratings.reputation(userId));
    }
}
