package com.group5.inventoryservice.inventory;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.AdjustmentRequest;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.inventory.dto.StockResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService service;
    private final StockService stock;

    public InventoryController(InventoryService service, StockService stock) {
        this.service = service;
        this.stock = stock;
    }

    @PostMapping("/intake")
    @ResponseStatus(HttpStatus.CREATED)
    // GATEWAY CHỈ CHECK TOKEN (SRS KHÔNG CÓ PRIVILEGE INVENTORY.*) -> SERVICE TỰ CHẶN, CHỈ ADMIN ĐƯỢC NHẬP KHO
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BalanceResponse> intake(@Valid @RequestBody IntakeRequest request, CurrentUser currentUser) {
        return ApiResponse.of(service.intake(request, currentUser));
    }

    @GetMapping("/{skuId}")
    public ApiResponse<StockResponse> available(@PathVariable UUID skuId) {
        return ApiResponse.of(stock.available(skuId));
    }

    @GetMapping
    public ApiResponse<List<StockResponse>> available(@RequestParam @NotEmpty List<@NotNull UUID> skuIds) {
        return ApiResponse.list(stock.available(skuIds));
    }

    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BalanceResponse> adjust(@Valid @RequestBody AdjustmentRequest request,
                                               CurrentUser currentUser) {
        return ApiResponse.of(service.adjust(request, currentUser));
    }
}
