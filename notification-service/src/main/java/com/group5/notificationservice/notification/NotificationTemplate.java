package com.group5.notificationservice.notification;

import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public enum NotificationTemplate {

    BID_OUTBID("BidOutbid", "Your bid was exceeded", "Your bid for auction %s was exceeded. Current price: %s %s.",
            new String[]{"outbidUserId"}, "auctionId", "amount", "currency"),
    AUCTION_WON("AuctionWon", "You won the auction", "You won auction %s for %s %s. Pay before %s.",
            new String[]{"winnerId"}, "auctionId", "finalPrice", "currency", "paymentDueAt"),

    SECOND_CHANCE_OFFER("SecondChanceOfferReady", "Second chance offer",
            "You have a second chance offer for auction %s at %s %s. Respond before %s.",
            new String[]{"bidderId"}, "auctionId", "amount", "currency", "expiresAt"),
    SECOND_CHANCE_ACCEPTED("SecondChanceAccepted", "Offer accepted",
            "Your second chance offer %s for auction %s was accepted.", new String[]{"sellerId"}, "offerId", "auctionId"),
    SECOND_CHANCE_DECLINED("SecondChanceDeclined", "Offer declined",
            "Your second chance offer %s for auction %s was declined.", new String[]{"sellerId"}, "offerId", "auctionId"),
    SECOND_CHANCE_EXPIRED("SecondChanceExpired", "Offer expired",
            "Your second chance offer %s for auction %s expired.", new String[]{"sellerId"}, "offerId", "auctionId"),

    ORDER_CREATED("OrderCreated", "Order placed", "Your order %s was placed",
            new String[]{"buyerId"}, "orderNumber"),
    ORDER_COMPLETED("OrderCompleted", "Order receipt", "Your order %s is complete. Your receipt is ready.",
            new String[]{"buyerId"}, "orderNumber"),
    ORDER_CANCELLED("OrderCancelled", "Order cancelled", "Your order %s was cancelled",
            new String[]{"buyerId"}, "orderNumber"),
    CHECKOUT_PAYMENT_CONFIRMED("CheckoutPaymentConfirmed", "Payment received",
            "Payment received for order group %s: %s %s.",
            new String[]{"buyerId"}, "orderGroupId", "totalAmount", "currency"),
    // Giữ type/preference đã lưu; ShipmentCreated còn PENDING_TRACKING nên chờ event pickup có tracking.
    SHIPMENT_CREATED("ShipmentPickedUp", "Order shipped", "Your order %s has shipped with %s. Tracking code: %s.",
            new String[]{"buyerId"}, "orderId", "carrier", "trackingCode"),
    SHIPMENT_DELIVERED("ShipmentDelivered", "Order delivered", "Your order %s was delivered.",
            new String[]{"buyerId"}, "orderId"),
    SHIPMENT_FAILED("ShipmentFailed", "Delivery problem", "Delivery failed for order %s. Please contact Support.",
            new String[]{"buyerId"}, "orderId"),
    SHIPMENT_RETURNED("ShipmentReturned", "Delivery problem", "Your order %s was returned. Please contact Support.",
            new String[]{"buyerId"}, "orderId"),
    ORDER_REFUND_REQUESTED("OrderRefundRequested", "Refund requested", "Refund requested for order %s: %s %s. Reason: %s",
            new String[]{"buyerId"}, "orderId", "amount", "currency", "reason"),
    ORDER_REFUND_COMPLETED("OrderRefundCompleted", "Refund sent", "Refund sent for order %s: %s %s.",
            new String[]{"buyerId"}, "orderId", "amount", "currency"),
    AUCTION_CANCELLED("AuctionCancelled", "Auction cancelled", "Auction %s was cancelled. Reason: %s",
            new String[]{"bidderIds", "sellerId"}, "auctionId", "reason"),
    AUCTION_PAYMENT_TIMEOUT("AuctionPaymentTimeout", "Auction order closed without payment",
            "The winner's order for auction %s was closed without payment. The seller can now offer it to the next bidder. Order: %s.",
            new String[]{"winnerId", "sellerId"}, "auctionId", "orderId"),
    SELLER_APPLICATION_APPROVED("SellerApplicationApproved", "Seller application approved",
            "Your seller application for %s was approved. Sign in again to open your seller tools.",
            new String[]{"userId"}, "companyName"),
    SELLER_APPLICATION_REJECTED("SellerApplicationRejected", "Seller application rejected",
            "Your seller application for %s was rejected. You can update it and apply again. Reason: %s",
            new String[]{"userId"}, "companyName", "reason");

    private final String eventType;
    private final List<String> recipientFields;
    private final String title;
    private final String contentFormat;
    private final List<String> contentFields;

    NotificationTemplate(String eventType, String title, String contentFormat,
                         String[] recipientFields, String... contentFields) {
        this.eventType = eventType;
        this.recipientFields = List.of(recipientFields);
        this.title = title;
        this.contentFormat = contentFormat;
        this.contentFields = List.of(contentFields);
    }

    public static Optional<NotificationTemplate> forEventType(String eventType) {
        return Arrays.stream(values())
                .filter(template -> template.eventType.equals(eventType))
                .findFirst();
    }

    // Render và kiểm tra toàn bộ người nhận trước khi lưu; cùng một user chỉ nhận một lần/event.
    public List<Notification> render(JsonNode payload, String frontendBaseUrl) {
        Set<UUID> recipients = new LinkedHashSet<>();
        for (String field : recipientFields) {
            JsonNode value = payload.path(field);
            if (isAdditiveRecipient(field) && (value.isMissingNode() || value.isNull())) {
                // Payload v1 cũ không có đủ audience: không suy đoán buyer/bidder từ actor hay ID khác.
                continue;
            }
            if ("bidderIds".equals(field)) {
                JsonNode bidders = value;
                if (!bidders.isArray()) {
                    throw new IllegalArgumentException(eventType + " payload has no " + field);
                }
                // Mảng rỗng hợp lệ khi phiên đấu giá chưa có bid; seller vẫn nhận email.
                bidders.forEach(bidder -> recipients.add(recipientId(bidder, field)));
            } else {
                recipients.add(recipientId(value, field));
            }
        }
        // Refund lịch sử vẫn được consumer đánh dấu processed mà không tạo email sai người nhận.
        if (recipients.isEmpty()) {
            return List.of();
        }
        Object[] values = contentFields.stream().map(field -> requiredText(payload, field)).toArray();
        String content = contentFormat.formatted(values);
        if (this == BID_OUTBID || this == AUCTION_WON) content += " View auction: "
                + frontendBaseUrl.replaceAll("/+$", "") + "/auctions/" + UUID.fromString(requiredText(payload, "auctionId"));
        if (this == SECOND_CHANCE_OFFER) content += " View your offers: " + frontendBaseUrl.replaceAll("/+$", "") + "/my-bids";
        if (this == SHIPMENT_DELIVERED) {
            UUID orderId = UUID.fromString(requiredText(payload, "orderId"));
            content += " Rate your order: " + frontendBaseUrl.replaceAll("/+$", "") + "/order/" + orderId;
        }
        String emailHtml = null;
        if (this == ORDER_COMPLETED) {
            UUID.fromString(requiredText(payload, "orderId"));
            // Commerce đã escape biên nhận snapshot; giữ riêng HTML với nội dung in-app.
            emailHtml = requiredText(payload, "receiptHtml");
        }
        String renderedContent = content;
        String renderedHtml = emailHtml;
        return recipients.stream().map(userId -> notification(userId, renderedContent, renderedHtml)).toList();
    }

    private boolean isAdditiveRecipient(String field) {
        return (this == ORDER_REFUND_REQUESTED || this == ORDER_REFUND_COMPLETED) && "buyerId".equals(field)
                || this == AUCTION_CANCELLED && "bidderIds".equals(field);
    }

    private Notification notification(UUID userId, String content, String emailHtml) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(name());
        notification.setTitle(title);
        notification.setContent(content);
        notification.setEmailHtml(emailHtml);
        return notification;
    }

    private UUID recipientId(JsonNode value, String field) {
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(eventType + " payload has no valid " + field);
        }
        return UUID.fromString(value.asString());
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(eventType + " payload has no " + field);
        }
        return value.asString();
    }
}
