package com.group5.catalogservice.product.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class SkuRequest {

    @NotBlank(message = "SKU code cannot be empty")
    @Size(max = 100, message = "SKU code cannot exceed 100 characters")
    private String skuCode;

    @NotBlank(message = "Color cannot be empty")
    @Size(max = 50, message = "Color cannot exceed 50 characters")
    private String color;

    @NotBlank(message = "Size cannot be empty")
    @Size(max = 50, message = "Size cannot exceed 50 characters")
    private String size;

    @NotNull(message = "Price cannot be empty")
    @DecimalMin(value = "0.00", inclusive = false,
            message = "Price must be greater than zero")
    @Digits(integer = 17, fraction = 2,
            message = "Price must have at most 17 integer digits and 2 decimal places")
    private BigDecimal price;
}
