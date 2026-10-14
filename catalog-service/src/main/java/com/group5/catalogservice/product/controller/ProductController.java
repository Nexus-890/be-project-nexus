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
import com.group5.common_security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import com.group5.catalogservice.product.dto.ProductRequest;

import com.group5.catalogservice.product.dto.ProductDetailResponse;
import jakarta.validation.groups.Default;
import org.springframework.validation.annotation.Validated;
import com.group5.catalogservice.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
        public ProductResponse create (
                @Valid @RequestBody ProductRequest request, CurrentUser currentUser){

            return productService.create(request, currentUser);
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
        public ProductResponse update (
                @PathVariable UUID id,
                @Validated({Default.class, ProductRequest.Update.class}) @RequestBody ProductRequest request,
                CurrentUser currentUser){
            return productService.update(id, request, currentUser);
        }

        @GetMapping("/{id}")
        public ProductDetailResponse getById (@PathVariable UUID id){
            return productService.getById(id);
        }
    }
}
