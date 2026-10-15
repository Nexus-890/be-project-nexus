package com.group5.catalogservice.product.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SkuRequest {

    @NotBlank
    @Size(max = 60)
    private String skuCode;

    @NotBlank
    @Size(max = 50)
    private String color;

    @NotBlank
    @Size(max = 50)
    private String size;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    private BigDecimal price;
}
