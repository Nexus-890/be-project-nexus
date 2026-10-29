package com.group5.notificationservice.notification;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.notificationservice.notification.dto.NotificationResponse;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(CurrentUser currentUser, int page, int size) {
        Page<Notification> result = repository.findByUserId(
                requireUserId(currentUser),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return result.map(NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(CurrentUser currentUser) {
        return repository.countByUserIdAndReadAtIsNull(requireUserId(currentUser));
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getContent(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }

    private UUID requireUserId(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new DomainException(NotificationErrorCode.UNAUTHENTICATED);
        }
        return currentUser.id();
    }
}
