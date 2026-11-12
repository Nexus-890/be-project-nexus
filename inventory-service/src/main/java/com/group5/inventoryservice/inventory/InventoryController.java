package com.group5.inventoryservice.inventory;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.inventory.dto.AdjustmentRequest;
import com.group5.inventoryservice.inventory.dto.BalanceResponse;
import com.group5.inventoryservice.inventory.dto.IntakeRequest;
import com.group5.inventoryservice.inventory.dto.MovementResponse;
import com.group5.inventoryservice.inventory.dto.StockResponse;
import com.group5.inventoryservice.shared.web.Pages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService service;
    private final StockService stock;
    private final InventoryQueryService queries;

    public InventoryController(InventoryService service, StockService stock, InventoryQueryService queries) {
        this.service = service;
        this.stock = stock;
        this.queries = queries;
    }

    @PostMapping("/intake")
    @ResponseStatus(HttpStatus.CREATED)
    // GATEWAY CHỈ CHECK TOKEN (SRS KHÔNG CÓ PRIVILEGE INVENTORY.*) -> SERVICE TỰ CHẶN: ADMIN HOẶC SELLER SỞ HỮU SKU
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    public ApiResponse<BalanceResponse> intake(@Valid @RequestBody IntakeRequest request, CurrentUser currentUser) {
        service.requireIntakeAccess(request, currentUser);
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

    @GetMapping("/balances")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER')")
    public ApiResponse<List<BalanceResponse>> balances(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(Pages.MAX_SIZE) int size,
            CurrentUser currentUser) {
        return Pages.response(queries.balances(skuId, warehouseId, page, size, currentUser));
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT_STAFF')")
    public ApiResponse<List<MovementResponse>> movements(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) UUID referenceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(Pages.MAX_SIZE) int size) {
        return Pages.response(queries.movements(skuId, warehouseId, type, referenceId, from, to, page, size));
    }
}
