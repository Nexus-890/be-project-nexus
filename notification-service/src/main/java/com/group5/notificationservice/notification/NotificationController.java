package com.group5.notificationservice.notification;

import com.group5.common_security.CurrentUser;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.notificationservice.notification.dto.NotificationResponse;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    public ApiResponse<List<NotificationResponse>> list(
            CurrentUser currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(NotificationErrorCode.DATA_INVALID);
        }
        var result = service.list(currentUser, page, size);
        return ApiResponse.page(result.getContent(), new PageInfo(result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    public ApiResponse<NotificationResponse.UnreadCount> unreadCount(CurrentUser currentUser) {
        return ApiResponse.of(new NotificationResponse.UnreadCount(service.unreadCount(currentUser)));
    }

    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    public void markAsRead(@PathVariable UUID id, CurrentUser currentUser) {
        service.markAsRead(id, currentUser);
    }

    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    public void markAllAsRead(CurrentUser currentUser) {
        service.markAllAsRead(currentUser);
    }
}
