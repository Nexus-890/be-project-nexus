package com.group5.catalogservice.product.controller;

import com.group5.common_security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import com.group5.catalogservice.product.dto.request.ProductRequest;
import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.dto.response.ProductDetailResponse;
import jakarta.validation.groups.Default;
import org.springframework.validation.annotation.Validated;
import com.group5.catalogservice.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.group5.catalogservice.product.dto.request.ChangeStatusRequest;
import com.group5.catalogservice.product.dto.response.PageResponse;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
    public ProductResponse create(
            @Valid @RequestBody ProductRequest request, CurrentUser currentUser) {

        return productService.create(request, currentUser);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public ProductResponse update(
            @PathVariable UUID id,
            @Validated({Default.class, ProductRequest.Update.class}) @RequestBody ProductRequest request,
            CurrentUser currentUser) {
        return productService.update(id, request, currentUser);
    }

    @GetMapping("/{id}")
    public ProductDetailResponse getById(@PathVariable UUID id) {
        return productService.getById(id);
    }
    @GetMapping
    public PageResponse<ProductResponse> getProductsByCategory(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return PageResponse.from(productService.getProductsByCategory(categoryId, page, size));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.LIST')")
    public PageResponse<ProductResponse> getMyProducts(
            CurrentUser currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return PageResponse.from(productService.getMyProducts(currentUser, page, size));
    }
    @PreAuthorize("hasRole('SELLER')")
    @PatchMapping("/{id}/status")
    public ProductResponse changeStatusProduct(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request,
            CurrentUser currentUser
    ) {
        return productService.changeStatusProduct(id, request, currentUser);
    }
}
