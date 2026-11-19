package com.group5.notificationservice.preference;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.notificationservice.preference.dto.*;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/preferences")
@PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
public class NotificationPreferenceController {
    private final NotificationPreferenceService service;
    public NotificationPreferenceController(NotificationPreferenceService service) { this.service = service; }
    @GetMapping
    public ApiResponse<NotificationPreferences> get(CurrentUser user) { return ApiResponse.of(service.get(id(user))); }
    @PutMapping
    @PreAuthorize("hasAuthority('NOTIFICATION.MANAGE_PREFERENCE')")
    public ApiResponse<NotificationPreferences> update(CurrentUser user, @Valid @RequestBody UpdateNotificationPreferences body) {
        return ApiResponse.of(service.update(id(user), body));
    }
    private UUID id(CurrentUser user) {
        if (user == null || user.id() == null) throw new DomainException(NotificationErrorCode.UNAUTHENTICATED);
        return user.id();
    }
}
