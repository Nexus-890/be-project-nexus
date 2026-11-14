package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OrderReceiptConfirmationController {
    private final OrderCompletionService completions;
    private final OrderService orders;

    public record ReceiptIssueRequest(@NotBlank @Size(max = 500) String reason) { }

    @PostMapping("/api/v1/orders/{id}/confirm-receipt")
    @PreAuthorize("hasAuthority('ORDER.VIEW') and hasRole('BUYER')")
    public ApiResponse<OrderResponse> confirm(CurrentUser user, @PathVariable UUID id) {
        completions.confirm(user, id);
        return ApiResponse.of(orders.getOrder(user, id));
    }

    @PostMapping("/api/v1/orders/{id}/receipt-issue")
    @PreAuthorize("hasAuthority('ORDER.VIEW') and hasRole('BUYER')")
    public ApiResponse<OrderResponse> reportIssue(CurrentUser user, @PathVariable UUID id,
                                                 @Valid @RequestBody ReceiptIssueRequest request) {
        completions.reportIssue(user, id, request.reason());
        return ApiResponse.of(orders.getOrder(user, id));
    }
}
