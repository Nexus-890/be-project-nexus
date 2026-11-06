package com.group5.notificationservice.delivery;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.notificationservice.delivery.dto.DeliveryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class DeliveryController {

    private final DeliveryQueryService service;

    public DeliveryController(DeliveryQueryService service) {
        this.service = service;
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    public ApiResponse<List<DeliveryResponse>> deliveries(@PathVariable UUID id, CurrentUser currentUser) {
        return ApiResponse.of(service.deliveries(id, currentUser));
    }
}
