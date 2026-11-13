package com.group5.commerceservice.order;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.order.dto.request.CreateOrderRequest.ShippingAddress;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuctionShippingController {
    private final AuctionShippingService shipping;

    @PostMapping("/api/v1/orders/{id}/shipping-confirmation")
    @PreAuthorize("hasAuthority('CHECKOUT.START') and hasRole('BUYER')")
    public ApiResponse<AuctionShippingService.Confirmation> confirm(CurrentUser user, @PathVariable UUID id,
                                                                   @Valid @RequestBody ShippingAddress address) {
        return ApiResponse.of(shipping.confirm(user, id, address));
    }
}
