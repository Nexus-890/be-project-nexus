package com.group5.notificationservice.delivery;

import com.group5.common_core.model.ApiResponse;
import com.group5.notificationservice.delivery.dto.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/notifications/deliveries")
@PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF') and hasAuthority('NOTIFICATION.VIEW')")
public class AdminDeliveryController {
    private final AdminDeliveryQuery query;
    public AdminDeliveryController(AdminDeliveryQuery query) { this.query = query; }

    @GetMapping
    public ApiResponse<List<NotificationDeliverySummary>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String errorCode) {
        return query.list(page, size, status, channel, from, to, errorCode);
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationDeliveryDetail> detail(@PathVariable UUID id) { return ApiResponse.of(query.detail(id)); }
}
