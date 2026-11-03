package com.group5.catalogservice.product.controller;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.common_security.CurrentUser;
import com.group5.common_core.model.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SkuController {

    private final SkuService skuService;

    public SkuController(SkuService skuService) {
        this.skuService = skuService;
    }

    // Yêu cầu quyền PRODUCT.UPDATE và trả về mã 201 Created.
    @PostMapping("/products/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public ApiResponse<SkuResponse> create(
            @PathVariable UUID productId,
            @Valid @RequestBody SkuRequest request,
            CurrentUser currentUser) {

        return ApiResponse.of(skuService.create(productId, request, currentUser));
    }

    // Yêu cầu quyền PRODUCT.UPDATE và trả về mã 200 OK.
    @PutMapping("/skus/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public ApiResponse<SkuResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody SkuRequest request,
            CurrentUser currentUser) {

        return ApiResponse.of(skuService.update(id, request, currentUser));
    }

    // Điểm cuối công khai khi được cấu hình bảo mật cho phép
    @GetMapping("/skus/{id}")
    public ApiResponse<SkuResponse> getById(@PathVariable UUID id) {
        return ApiResponse.of(skuService.getById(id));
    }

    @GetMapping("/products/{productId}/skus")
    public ApiResponse<List<SkuResponse>> getByProductId(@PathVariable UUID productId) {
        return ApiResponse.list(skuService.getByProductId(productId));
    }
}
