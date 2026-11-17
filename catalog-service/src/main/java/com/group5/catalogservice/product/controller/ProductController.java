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
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import org.springframework.data.domain.Page;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN') and hasAuthority('PRODUCT.DELETE')")
    public void deleteProduct(@PathVariable UUID id, CurrentUser currentUser) {
        productService.deleteProduct(id, currentUser);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCT.CREATE')")
    public ApiResponse<ProductResponse> create(
            @Valid @RequestBody ProductRequest request, CurrentUser currentUser) {

        return ApiResponse.of(productService.create(request, currentUser));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public ApiResponse<ProductResponse> update(
            @PathVariable UUID id,
            @Validated({Default.class, ProductRequest.Update.class}) @RequestBody ProductRequest request,
            CurrentUser currentUser) {
        return ApiResponse.of(productService.update(id, request, currentUser));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> getById(@PathVariable UUID id) {
        return ApiResponse.of(productService.getById(id));
    }
    @GetMapping
    public ApiResponse<List<ProductResponse>> getProductsByCategory(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "all") String listingType,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return pageResponse(productService.searchProducts(categoryId, q, sort, listingType, maxPrice, page, size));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.LIST')")
    public ApiResponse<List<ProductResponse>> getMyProducts(
            CurrentUser currentUser,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(required = false) Boolean auctionEnable,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return pageResponse(productService.getMyProducts(currentUser, status, auctionEnable, page, size));
    }

    @GetMapping("/mine/{id}")
    @PreAuthorize("hasRole('SELLER') and hasAuthority('PRODUCT.LIST')")
    public ApiResponse<ProductDetailResponse> getMyProduct(@PathVariable UUID id, CurrentUser currentUser) {
        return ApiResponse.of(productService.getMyProduct(id, currentUser));
    }
    @PreAuthorize(
            "hasAnyRole('SELLER', 'ADMIN') and hasAuthority('PRODUCT.UPDATE')"
    )
    @PatchMapping("/{id}/status")
    public ApiResponse<ProductResponse> changeStatusProduct(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeStatusRequest request,
            CurrentUser currentUser
    ) {
        return ApiResponse.of(productService.changeStatusProduct(id, request, currentUser));
    }

    private ApiResponse<List<ProductResponse>> pageResponse(Page<ProductResponse> products) {
        return ApiResponse.page(products.getContent(), new PageInfo(
                products.getNumber(), products.getSize(), products.getTotalElements(), products.getTotalPages()));
    }
}
