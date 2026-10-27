package com.group5.notificationservice.notification;

import com.group5.common_security.CurrentUser;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.notificationservice.notification.dto.NotificationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public NotificationResponse.Page list(
            CurrentUser currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        requireViewPrivilege(currentUser);
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
        return service.list(currentUser, page, size);
    }

    @GetMapping("/unread-count")
    public NotificationResponse.UnreadCount unreadCount(CurrentUser currentUser) {
        requireViewPrivilege(currentUser);
        return new NotificationResponse.UnreadCount(service.unreadCount(currentUser));
    }

    private void requireViewPrivilege(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        if (!currentUser.hasPrivilege("NOTIFICATION.VIEW")) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
    }
}
