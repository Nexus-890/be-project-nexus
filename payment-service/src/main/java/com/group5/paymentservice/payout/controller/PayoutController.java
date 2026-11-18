package com.group5.paymentservice.payout.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.PayoutListRequest;
import com.group5.paymentservice.payout.dto.response.PayoutView;
import com.group5.paymentservice.payout.service.PayoutQueryService;
import com.group5.paymentservice.payout.service.PayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/payments/payouts")
@RequiredArgsConstructor
public class PayoutController {
    private final PayoutQueryService queries;
    private final PayoutService payouts;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<PayoutView>> list(CurrentUser user, @Valid @ModelAttribute PayoutListRequest request) {
        var result = queries.admin(user, request);
        return ApiResponse.page(result.content(), result.page());
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('SELLER')")
    public ApiResponse<List<PayoutView>> mine(CurrentUser user, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = queries.mine(user, page, size);
        return ApiResponse.page(result.content(), result.page());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SELLER','ADMIN')")
    public ApiResponse<PayoutView> detail(CurrentUser user, @PathVariable UUID id) {
        return ApiResponse.of(queries.detail(user, id));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PayoutView> retry(CurrentUser user, @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        payouts.retry(user, id, key);
        return ApiResponse.of(queries.detail(user, id));
    }
}
