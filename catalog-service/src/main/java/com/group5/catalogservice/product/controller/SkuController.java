package com.group5.catalogservice.product.controller;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.service.SkuService;
import com.group5.common_security.CurrentUser;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SkuController {

    private final SkuService skuService;

    public SkuController(SkuService skuService) {
        this.skuService = skuService;
    }

    @PostMapping("/products/{productId}/skus")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public SkuResponse create(
            @PathVariable UUID productId,
            @Valid @RequestBody SkuRequest request,
            CurrentUser currentUser) {

        return skuService.create(productId, request, currentUser);
    }

    @PutMapping("/skus/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE')")
    public SkuResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody SkuRequest request,
            CurrentUser currentUser) {

        return skuService.update(id, request, currentUser);
    }

    @GetMapping("/skus/{id}")
    public SkuResponse getById(@PathVariable UUID id) {
        return skuService.getById(id);
    }
}