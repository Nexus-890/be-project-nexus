package com.group5.inventoryservice.reservation.controller;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.inventoryservice.reservation.service.ReservationQueryService;
import com.group5.inventoryservice.reservation.service.ReservationQueryService.OrderReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/inventory/reservations")
@RequiredArgsConstructor
public class InternalReservationController {
    private final ReservationQueryService reservations;

    @GetMapping
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<List<OrderReservation>> get(@RequestParam UUID orderId, CurrentUser buyer) {
        return ApiResponse.list(reservations.byOrder(orderId, buyer.id()));
    }
}
