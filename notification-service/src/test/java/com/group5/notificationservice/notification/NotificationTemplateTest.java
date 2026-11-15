package com.group5.notificationservice.notification;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateTest {

    @Test
    void orderCreatedRendersTheBuyerAndTheOrderNumber() {
        Notification notification = NotificationTemplate.ORDER_CREATED.render(JsonMapper.shared().readTree("""
                {"orderId":"00000000-0000-4000-8000-000000000001",
                 "buyerId":"00000000-0000-4000-8000-000000000002",
                 "orderNumber":"NX-20261120-0001","orderType":"STANDARD","referenceId":null}
                """));

        assertThat(notification.getUserId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000002"));
        assertThat(notification.getType()).isEqualTo("ORDER_CREATED");
        assertThat(notification.getTitle()).isEqualTo("Order placed");
        assertThat(notification.getContent()).isEqualTo("Your order NX-20261120-0001 was placed");
    }

    @Test
    void orderCreatedAndCompletedHaveTemplates() {
        assertThat(NotificationTemplate.forEventType("OrderCreated")).contains(NotificationTemplate.ORDER_CREATED);
        assertThat(NotificationTemplate.forEventType("OrderCompleted")).contains(NotificationTemplate.ORDER_COMPLETED);
        assertThat(NotificationTemplate.forEventType("CategoryCreated")).isEmpty();
        assertThat(NotificationTemplate.forEventType("OrderPaid")).isEmpty();
    }

    @Test
    void orderCancelledTellsTheBuyerWhichOrder() {
        Notification notification = NotificationTemplate.forEventType("OrderCancelled").orElseThrow()
                .render(JsonMapper.shared().readTree("""
                        {"orderId":"00000000-0000-4000-8000-000000000001",
                         "buyerId":"00000000-0000-4000-8000-000000000002",
                         "orderNumber":"NX-20261120-0001","reason":"PAYMENT_DEADLINE_EXPIRED"}
                        """));

        assertThat(notification.getUserId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000002"));
        assertThat(notification.getType()).isEqualTo("ORDER_CANCELLED");
        assertThat(notification.getContent()).isEqualTo("Your order NX-20261120-0001 was cancelled");
    }

    @Test
    void orderCreatedWithoutBuyerIsRejected() {
        assertThatThrownBy(() -> NotificationTemplate.ORDER_CREATED.render(
                JsonMapper.shared().readTree("{\"orderNumber\":\"NX-1\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerId");
    }

    @Test
    void orderCompletedKeepsTheReceiptSeparateFromTheInAppSummary() {
        String html = "<!doctype html><html><body><h1>Receipt NX-204</h1><p>Caf&eacute; &amp; tea</p></body></html>";
        Notification notification = NotificationTemplate.ORDER_COMPLETED.render(JsonMapper.shared().valueToTree(
                java.util.Map.of("orderId", "00000000-0000-4000-8000-000000000001",
                        "buyerId", "00000000-0000-4000-8000-000000000002",
                        "orderNumber", "NX-204", "receiptHtml", html)));

        assertThat(notification.getUserId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000002"));
        assertThat(notification.getType()).isEqualTo("ORDER_COMPLETED");
        assertThat(notification.getTitle()).isEqualTo("Order receipt");
        assertThat(notification.getContent()).isEqualTo("Your order NX-204 is complete. Your receipt is ready.");
        assertThat(notification.getEmailHtml()).isEqualTo(html);
    }

    @Test
    void completionWithoutAReceiptIsRejectedBeforeCreatingANotification() {
        assertThatThrownBy(() -> NotificationTemplate.ORDER_COMPLETED.render(JsonMapper.shared().readTree("""
                {"orderId":"00000000-0000-4000-8000-000000000001",
                 "buyerId":"00000000-0000-4000-8000-000000000002","orderNumber":"NX-204"}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receiptHtml");
    }
}
