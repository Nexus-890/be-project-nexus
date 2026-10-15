package com.group5.catalogservice.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class ProductRequest {
    public interface Update {}

    @NotBlank(message = "Product name can't be empty")
    @Size(max = 255, message = "The product name can't be longer than 255 characters")
    private String name;

    private String description;

    @NotNull(message = "The category can't be empty")
    private UUID categoryId;

    @NotNull(groups = Update.class, message = "Auction enable is required for update")
    private Boolean auctionEnable;
}
