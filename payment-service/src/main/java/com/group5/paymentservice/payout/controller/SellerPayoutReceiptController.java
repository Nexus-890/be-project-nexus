package com.group5.paymentservice.payout.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.ManualReceiptView;
import com.group5.paymentservice.payout.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments/payouts/{payoutId}/manual-attempts/{attemptId}")
@PreAuthorize("hasRole('SELLER')")
@RequiredArgsConstructor
public class SellerPayoutReceiptController {
    private final SellerPayoutReceiptService receipts;
    private final PayoutDisputeService disputes;

    @PostMapping("/receipt-confirmations")
    public ApiResponse<ManualReceiptView> confirm(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReceiptCommand body) {
        return ApiResponse.of(
                receipts.projection(receipts.confirm(user, payoutId, attemptId, key, body)));
    }

    @PostMapping("/receipt-disputes")
    public ResponseEntity<ApiResponse<ManualReceiptView>> dispute(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DisputeCommand body) {
        var result = disputes.open(user, payoutId, attemptId, key, body);
        return ResponseEntity.status(result.created() && !result.replay() ? 201 : 200)
                .body(ApiResponse.of(receipts.projection(result)));
    }
}
