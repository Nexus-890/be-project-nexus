package com.group5.catalogservice.product.controller;


import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.service.ProductService;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.autoconfigure.NexusSecurityProperties;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public Page<ProductResponse> getProductsByCategory(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return productService.getProductsByCategory(
                categoryId, page, size
        );
    }

    @GetMapping("/mine")
    public Page<ProductResponse> getMyProducts(
            @AuthenticationPrincipal NexusSecurityProperties.Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        CurrentUser currentUser = CurrentUser.from(jwt);

        return productService.getMyProducts(
                currentUser.id(), page, size);
    }
}
