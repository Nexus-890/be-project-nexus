package com.group5.inventoryservice.reservation.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.dto.request.ReserveRequest;
import com.group5.inventoryservice.reservation.dto.response.ReservationResponse;
import com.group5.inventoryservice.reservation.dto.response.ReservationView;
import com.group5.inventoryservice.reservation.service.ReservationQueryService;
import com.group5.inventoryservice.reservation.service.ReservationService;
import com.group5.inventoryservice.shared.web.Pages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1/inventory/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationQueryService reservationQueries;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request,
                                                    CurrentUser currentUser) {
        return ApiResponse.of(reservationService.reserve(request, currentUser));
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void confirm(@PathVariable UUID id, CurrentUser currentUser) {
        reservationService.confirm(id, currentUser);
    }

    @PostMapping("/{id}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void release(@PathVariable UUID id, CurrentUser currentUser) {
        reservationService.release(id, currentUser);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT_STAFF')")
    public ApiResponse<List<ReservationView>> list(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(Pages.MAX_SIZE) int size) {
        return Pages.response(reservationQueries.list(orderId, skuId, status, page, size));
    }
}
