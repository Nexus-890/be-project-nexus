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
    void outbidAndWinnerTemplatesTargetOnlyTheirSpecificUser() {
        UUID auction = UUID.randomUUID(), loser = UUID.randomUUID(), winner = UUID.randomUUID();
        var outbid = NotificationTemplate.BID_OUTBID.render(JsonMapper.shared().valueToTree(java.util.Map.of(
                "auctionId", auction.toString(), "outbidUserId", loser.toString(), "bidderId", winner.toString(),
                "amount", "200000.00", "currency", "VND")), FRONTEND);
        assertThat(outbid).singleElement().satisfies(n -> {
            assertThat(n.getUserId()).isEqualTo(loser);
            assertThat(n.getContent()).contains(auction.toString(), "200000.00", "VND");
        });
        var won = NotificationTemplate.AUCTION_WON.render(JsonMapper.shared().valueToTree(java.util.Map.of(
                "auctionId", auction.toString(), "winnerId", winner.toString(), "finalPrice", "200000.00",
                "currency", "VND", "paymentDueAt", "2026-11-20T10:00:00Z")), FRONTEND);
        assertThat(won).singleElement().satisfies(n -> {
            assertThat(n.getUserId()).isEqualTo(winner);
            assertThat(n.getContent()).contains("2026-11-20T10:00:00Z", "200000.00");
        });
    }

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
    void sellerApplicationDecisionsNotifyTheApplicant() {
        String applicant = "00000000-0000-4000-8000-000000000003";
        Notification approved = NotificationTemplate.forEventType("SellerApplicationApproved").orElseThrow()
                .render(JsonMapper.shared().readTree("""
                        {"userId":"%s","actorId":"00000000-0000-4000-8000-000000000004",
                         "companyName":"Ceramic House","approvedAt":"2026-11-17T04:00:00Z"}
                        """.formatted(applicant)), FRONTEND).getFirst();
        assertThat(approved.getUserId()).isEqualTo(UUID.fromString(applicant));
        assertThat(approved.getType()).isEqualTo("SELLER_APPLICATION_APPROVED");
        assertThat(approved.getContent()).contains("Ceramic House", "approved");

        Notification rejected = NotificationTemplate.forEventType("SellerApplicationRejected").orElseThrow()
                .render(JsonMapper.shared().readTree("""
                        {"userId":"%s","actorId":"00000000-0000-4000-8000-000000000004",
                         "companyName":"Ceramic House","reason":"Business licence is missing.",
                         "rejectedAt":"2026-11-17T04:00:00Z"}
                        """.formatted(applicant)), FRONTEND).getFirst();
        assertThat(rejected.getUserId()).isEqualTo(UUID.fromString(applicant));
        assertThat(rejected.getType()).isEqualTo("SELLER_APPLICATION_REJECTED");
        assertThat(rejected.getContent()).isEqualTo("Your seller application for Ceramic House was rejected. "
                + "You can update it and apply again. Reason: Business licence is missing.");
        assertThat(NotificationTemplate.forEventType("UserRoleAssigned")).isEmpty();
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
                Arguments.of("ShipmentPickedUp", "{" + buyer + "," + order
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

    // Trường người nhận gốc vẫn bắt buộc; chỉ field additive của v1 lịch sử mới được bỏ qua.
    @ParameterizedTest
    @MethodSource("requiredRecipientSamples")
    void missingRequiredRecipientIsRejected(String eventType, String payload, int ignoredCount, String ignoredContent) {
        var node = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree(payload);
        String field = eventType.equals("AuctionCancelled") ? "sellerId"
                : eventType.equals("AuctionPaymentTimeout") ? "winnerId" : "buyerId";
        node.remove(field);
        assertThatThrownBy(() -> NotificationTemplate.forEventType(eventType).orElseThrow().render(node, FRONTEND))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    static Stream<Arguments> requiredRecipientSamples() {
        return eventSamples().filter(sample -> !List.of("OrderRefundRequested", "OrderRefundCompleted")
                .contains(sample.get()[0]));
    }

    @ParameterizedTest
    @MethodSource("refundEventTypes")
    void historicalRefundWithoutBuyerDoesNotGuessTheOperatorOrSeller(String eventType) {
        var payload = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree("""
                {"orderId":"00000000-0000-4000-8000-000000000001","amount":"50000.00","currency":"VND",
                 "reason":"Damaged","actorId":"00000000-0000-4000-8000-000000000003",
                 "sellerId":"00000000-0000-4000-8000-000000000004"}
                """);
        NotificationTemplate template = NotificationTemplate.forEventType(eventType).orElseThrow();

        assertThat(template.render(payload, FRONTEND)).isEmpty();
        payload.putNull("buyerId");
        assertThat(template.render(payload, FRONTEND)).isEmpty();
    }

    static Stream<String> refundEventTypes() {
        return Stream.of("OrderRefundRequested", "OrderRefundCompleted");
    }

    @Test
    void historicalAuctionWithoutBidderSnapshotStillNotifiesOnlyTheSeller() {
        var payload = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree("""
                {"auctionId":"a-259","reason":"Cancelled",
                 "actorId":"00000000-0000-4000-8000-000000000003",
                 "sellerId":"00000000-0000-4000-8000-000000000004"}
                """);
        UUID seller = UUID.fromString("00000000-0000-4000-8000-000000000004");
        assertThat(NotificationTemplate.AUCTION_CANCELLED.render(payload, FRONTEND))
                .extracting(Notification::getUserId).containsExactly(seller);
        payload.putNull("bidderIds");
        assertThat(NotificationTemplate.AUCTION_CANCELLED.render(payload, FRONTEND))
                .extracting(Notification::getUserId).containsExactly(seller);
    }

    @ParameterizedTest
    @MethodSource("malformedAdditiveRecipients")
    void presentMalformedAdditiveRecipientIsRejected(String eventType, String field, String invalidValue) {
        var payload = (tools.jackson.databind.node.ObjectNode) JsonMapper.shared().readTree("""
                {"orderId":"00000000-0000-4000-8000-000000000001","amount":"50000.00","currency":"VND",
                 "reason":"Cancelled","auctionId":"a-259","sellerId":"00000000-0000-4000-8000-000000000004"}
                """);
        payload.set(field, JsonMapper.shared().readTree(invalidValue));
        assertThatThrownBy(() -> NotificationTemplate.forEventType(eventType).orElseThrow().render(payload, FRONTEND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> malformedAdditiveRecipients() {
        return Stream.concat(refundEventTypes().flatMap(eventType -> Stream.of("\"\"", "\"invalid-uuid\"", "7")
                        .map(value -> Arguments.of(eventType, "buyerId", value))),
                Stream.of("\"invalid-list\"", "{}", "[null]", "[\"invalid-uuid\"]")
                        .map(value -> Arguments.of("AuctionCancelled", "bidderIds", value)));
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
        // Mảng bidders rỗng hợp lệ; phần tử không phải UUID vẫn bị từ chối.
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
    void shipmentCreationWaitsForPickupAndPickupRequiresTracking() {
        // Tạo shipment lúc chờ tracking không đồng nghĩa carrier đã nhận kiện hàng.
        JsonNode payload = JsonMapper.shared().readTree("""
                {"orderId":"order-259","buyerId":"00000000-0000-4000-8000-000000000002",
                 "carrier":"GHN","trackingCode":null}
                """);
        assertThat(NotificationTemplate.forEventType("ShipmentCreated")).isEmpty();
        assertThatThrownBy(() -> NotificationTemplate.SHIPMENT_CREATED.render(payload, FRONTEND))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trackingCode");
    }
}
