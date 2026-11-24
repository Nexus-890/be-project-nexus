package com.group5.commerceservice.order.summary;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OrderSummaryController {

    private final OrderSummaryService summaries;

    @GetMapping("/api/v1/orders/manage/summary")
    @PreAuthorize("hasAuthority('ORDER.LIST') and hasAnyRole('ADMIN','SUPPORT_STAFF')")
    public ApiResponse<OrderSummaryResponse> managed(CurrentUser user,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.of(summaries.managed(user, sellerId, days));
    }

    @GetMapping("/api/v1/orders/sales/summary")
    @PreAuthorize("hasAuthority('ORDER.LIST') and hasRole('SELLER')")
    public ApiResponse<OrderSummaryResponse> sales(CurrentUser user,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.of(summaries.sales(user, days));
    }
}
