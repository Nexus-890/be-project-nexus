package com.group5.catalogservice.product.dto.response;

import com.group5.catalogservice.product.enums.ProductStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponse {

    private UUID id;
    private String name;
    private String slug;
    private ProductStatus status;
    private UUID categoryId;
    private UUID sellerId;
}
