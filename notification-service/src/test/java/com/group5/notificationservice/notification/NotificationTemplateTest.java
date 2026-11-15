package com.group5.notificationservice.notification;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTemplateTest {

    private static final String FRONTEND = "https://nexus.example/";

    @Test
    void orderCreatedRendersTheBuyerAndTheOrderNumber() {
        Notification notification = NotificationTemplate.ORDER_CREATED.render(JsonMapper.shared().readTree("""
                {"orderId":"00000000-0000-4000-8000-000000000001",
                 "buyerId":"00000000-0000-4000-8000-000000000002",
                 "orderNumber":"NX-20261120-0001","orderType":"STANDARD","referenceId":null}
                """), FRONTEND).getFirst();

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
        // Giữ nội dung email hủy đơn đã có từ #159 khi đổi render sang nhiều người nhận.
        Notification notification = NotificationTemplate.forEventType("OrderCancelled").orElseThrow()
                .render(JsonMapper.shared().readTree("""
                        {"orderId":"00000000-0000-4000-8000-000000000001",
                         "buyerId":"00000000-0000-4000-8000-000000000002",
                         "orderNumber":"NX-20261120-0001","reason":"PAYMENT_DEADLINE_EXPIRED"}
                        """), FRONTEND).getFirst();

        assertThat(notification.getUserId()).isEqualTo(UUID.fromString("00000000-0000-4000-8000-000000000002"));
        assertThat(notification.getType()).isEqualTo("ORDER_CANCELLED");
        assertThat(notification.getContent()).isEqualTo("Your order NX-20261120-0001 was cancelled");
    }

    @Test
    void orderCreatedWithoutBuyerIsRejected() {
        assertThatThrownBy(() -> NotificationTemplate.ORDER_CREATED.render(
                JsonMapper.shared().readTree("{\"orderNumber\":\"NX-1\"}"), FRONTEND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerId");
    }

    @Test
    void orderCompletedKeepsTheReceiptSeparateFromTheInAppSummary() {
        String html = "<!doctype html><html><body><h1>Receipt NX-204</h1><p>Caf&eacute; &amp; tea</p></body></html>";
        Notification notification = NotificationTemplate.ORDER_COMPLETED.render(JsonMapper.shared().valueToTree(
                java.util.Map.of("orderId", "00000000-0000-4000-8000-000000000001",
                        "buyerId", "00000000-0000-4000-8000-000000000002",
                        "orderNumber", "NX-204", "receiptHtml", html)), FRONTEND).getFirst();

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
                """), FRONTEND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receiptHtml");
    }

    // Mỗi event có payload và nội dung riêng: bắt lỗi chọn sai trường tiền, lý do hoặc người nhận.
    @ParameterizedTest
    @MethodSource("eventSamples")
    void eachNewTemplateRendersItsRecipientsAndContent(String eventType, String payload,
                                                       int recipientCount, String expectedContent) {
        NotificationTemplate template = NotificationTemplate.forEventType(eventType).orElseThrow();
        List<Notification> rendered = template.render(JsonMapper.shared().readTree(payload), FRONTEND);

        assertThat(rendered).hasSize(recipientCount).allSatisfy(notification -> {
            assertThat(notification.getType()).isEqualTo(template.name());
            assertThat(notification.getContent()).contains(expectedContent);
            assertThat(notification.getEmailHtml()).isNull();
        });
    }

    static Stream<Arguments> eventSamples() {
        String buyer = "\"buyerId\":\"00000000-0000-4000-8000-000000000002\"";
        String order = "\"orderId\":\"00000000-0000-4000-8000-000000000001\"";
        return Stream.of(
                Arguments.of("OrderCancelled", "{" + buyer + "," + order
                        + ",\"orderNumber\":\"NX-259\",\"reason\":\"Expired\"}", 1, "NX-259 was cancelled"),
                Arguments.of("CheckoutPaymentConfirmed", "{" + buyer + ",\"orderGroupId\":\"group-259\","
                        + "\"totalAmount\":\"350000.50\",\"currency\":\"VND\"}", 1, "group-259: 350000.50 VND"),
                Arguments.of("ShipmentCreated", "{" + buyer + "," + order
                        + ",\"carrier\":\"GHN\",\"trackingCode\":\"GHN-259\"}", 1, "GHN. Tracking code: GHN-259"),
                Arguments.of("ShipmentDelivered", "{" + buyer + "," + order + "}", 1,
                        "Rate your order: https://nexus.example/order/00000000-0000-4000-8000-000000000001"),
                Arguments.of("ShipmentFailed", "{" + buyer + "," + order + "}", 1, "Please contact Support"),
                Arguments.of("ShipmentReturned", "{" + buyer + "," + order + "}", 1, "Please contact Support"),
                Arguments.of("OrderRefundRequested", "{" + buyer + "," + order
                        + ",\"amount\":\"50000.00\",\"currency\":\"VND\",\"reason\":\"Damaged\"}", 1,
                        "50000.00 VND. Reason: Damaged"),
                Arguments.of("OrderRefundCompleted", "{" + buyer + "," + order
                        + ",\"amount\":\"50000.00\",\"currency\":\"VND\"}", 1, "50000.00 VND"),
                Arguments.of("AuctionCancelled", "{\"auctionId\":\"auction-259\",\"reason\":\"Policy violation\","
                        + "\"bidderIds\":[\"00000000-0000-4000-8000-000000000002\"],"
                        + "\"sellerId\":\"00000000-0000-4000-8000-000000000004\"}", 2, "Policy violation"),
                Arguments.of("AuctionPaymentTimeout", "{\"auctionId\":\"auction-259\"," + order
                        + ",\"winnerId\":\"00000000-0000-4000-8000-000000000002\","
                        + "\"sellerId\":\"00000000-0000-4000-8000-000000000004\"}", 2, "payment deadline"));
    }

    // Không đánh dấu event thành công nếu thiếu một trường người nhận; Kafka còn có thể retry payload lỗi.
    @ParameterizedTest
    @MethodSource("eventSamples")
    void missingRecipientIsRejected(String eventType, String payload, int ignoredCount, String ignoredContent) {
        var node = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree(payload);
        String field = eventType.equals("AuctionCancelled") ? "bidderIds"
                : eventType.equals("AuctionPaymentTimeout") ? "winnerId" : "buyerId";
        node.remove(field);
        assertThatThrownBy(() -> NotificationTemplate.forEventType(eventType).orElseThrow().render(node, FRONTEND))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void auctionRecipientsAreDistinctAcrossBiddersAndSeller() {
        // Khử trùng cả nhiều bid của một người và seller trùng một ID trong danh sách.
        JsonNode payload = JsonMapper.shared().readTree("""
                {"auctionId":"a-259","reason":"Cancelled","bidderIds":[
                "00000000-0000-4000-8000-000000000002","00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000004"],
                "sellerId":"00000000-0000-4000-8000-000000000004"}
                """);
        assertThat(NotificationTemplate.AUCTION_CANCELLED.render(payload, FRONTEND))
                .extracting(Notification::getUserId).containsExactly(
                        UUID.fromString("00000000-0000-4000-8000-000000000002"),
                        UUID.fromString("00000000-0000-4000-8000-000000000004"));
    }

    @Test
    void auctionWithoutBidsStillNotifiesTheSeller() {
        // Mảng bidders rỗng hợp lệ; thiếu mảng hoặc phần tử không phải UUID thì từ chối.
        var payload = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree("""
                {"auctionId":"a-259","reason":"Cancelled","bidderIds":[],
                "sellerId":"00000000-0000-4000-8000-000000000004"}
                """);
        assertThat(NotificationTemplate.AUCTION_CANCELLED.render(payload, FRONTEND)).hasSize(1);
        payload.putArray("bidderIds").add("invalid-uuid");
        assertThatThrownBy(() -> NotificationTemplate.AUCTION_CANCELLED.render(payload, FRONTEND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shipmentWithoutTrackingDoesNotInventACode() {
        // Carrier chưa trả tracking là một payload hợp lệ của #202.
        JsonNode payload = JsonMapper.shared().readTree("""
                {"orderId":"order-259","buyerId":"00000000-0000-4000-8000-000000000002",
                 "carrier":"GHN","trackingCode":null}
                """);
        assertThat(NotificationTemplate.SHIPMENT_CREATED.render(payload, FRONTEND).getFirst().getContent())
                .contains("Pending carrier confirmation").doesNotContain("null");
    }
}
