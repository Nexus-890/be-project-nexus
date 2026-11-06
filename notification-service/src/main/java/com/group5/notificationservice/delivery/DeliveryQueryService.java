package com.group5.notificationservice.delivery;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.notificationservice.delivery.dto.DeliveryResponse;
import com.group5.notificationservice.notification.Notification;
import com.group5.notificationservice.notification.NotificationRepository;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

@Service
public class DeliveryQueryService {

    private final NotificationRepository notifications;
    private final DeliveryRepository deliveries;

    public DeliveryQueryService(NotificationRepository notifications, DeliveryRepository deliveries) {
        this.notifications = notifications;
        this.deliveries = deliveries;
    }

    // CHỈ CHỦ THÔNG BÁO XEM ĐƯỢC LỊCH SỬ GỬI, NGƯỜI KHÁC -> 403 NOTIFICATION_NOT_OWNER
    // KHÔNG TRẢ error_message VÀ EMAIL NGƯỜI NHẬN, CHỈ TRẢ errorCode
    @Transactional(readOnly = true)
    public List<DeliveryResponse> deliveries(UUID notificationId, CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new DomainException(NotificationErrorCode.UNAUTHENTICATED);
        }
        Notification notification = notifications.findById(notificationId)
                .orElseThrow(() -> new DomainException(NotificationErrorCode.NOT_FOUND));
        if (!currentUser.id().equals(notification.getUserId())) {
            throw new DomainException(NotificationErrorCode.NOT_OWNER);
        }

        Map<UUID, List<DeliveryResponse.Attempt>> attempts = deliveries.findAttemptsByNotification(notificationId)
                .stream()
                .collect(groupingBy(DeliveryRepository.AttemptRow::deliveryId, mapping(row ->
                        new DeliveryResponse.Attempt(row.id(), row.attemptNumber(), row.status(), row.attemptedAt(),
                                row.completedAt(), row.errorCode(), row.retryable()), toList())));
        return deliveries.findByNotification(notificationId).stream()
                .map(row -> new DeliveryResponse(row.id(), row.channel(), row.status(), row.attemptCount(),
                        row.nextAttemptAt(), row.createdAt(), row.updatedAt(),
                        List.copyOf(attempts.getOrDefault(row.id(), List.of()))))
                .toList();
    }
}
