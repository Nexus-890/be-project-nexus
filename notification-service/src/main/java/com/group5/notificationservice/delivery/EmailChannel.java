package com.group5.notificationservice.delivery;

import com.group5.notificationservice.notification.Notification;
import com.group5.notificationservice.notification.NotificationChannel;
import com.group5.notificationservice.notification.NotificationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(prefix = "nexus.notification", name = "email-enabled", havingValue = "true")
public class EmailChannel implements NotificationChannel {

    private final NotificationRepository notifications;
    private final DeliveryRepository deliveries;
    private final Clock clock;

    public EmailChannel(NotificationRepository notifications, DeliveryRepository deliveries, Clock clock) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    // KHÔNG GỬI SMTP TRONG LÚC NHẬN EVENT KAFKA: GHI DELIVERY PENDING/SKIPPED THEO PREFERENCE CÙNG TRANSACTION
    // DeliveryRetryJob SẼ GỬI LẦN ĐẦU VÀ CẢ CÁC LẦN GỬI LẠI -> SMTP CHẬM/SẬP KHÔNG LÀM KẸT CONSUMER
    @Override
    public void send(Notification notification) {
        notifications.flush();
        deliveries.insertEmail(notification.getId(), clock.instant());
    }
}
