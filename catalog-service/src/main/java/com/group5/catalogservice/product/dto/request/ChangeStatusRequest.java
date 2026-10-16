package com.group5.catalogservice.product.dto.request;

import com.group5.catalogservice.product.enums.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(
        @NotNull
        ProductStatus status
) {
}
