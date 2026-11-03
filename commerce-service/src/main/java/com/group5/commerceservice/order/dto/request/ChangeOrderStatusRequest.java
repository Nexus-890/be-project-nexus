package com.group5.commerceservice.order.dto.request;

import com.group5.commerceservice.order.OrderStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeOrderStatusRequest(
        @NotNull OrderStatus status,
        @NotBlank @Size(max = 500) String reason) {
}
