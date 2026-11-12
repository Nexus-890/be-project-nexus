package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;

/** Renders only stored order snapshots; no catalog, payment or shipment reads. */
@Component
public class OrderReceiptRenderer {
    public String render(Order order, OffsetDateTime completedAt) {
        return render(order, completedAt, true);
    }

    /** Không đưa thông tin liên hệ người nhận vào event và email bất đồng bộ. */
    public String renderForEmail(Order order, OffsetDateTime completedAt) {
        return render(order, completedAt, false);
    }

    private String render(Order order, OffsetDateTime completedAt, boolean includeRecipient) {
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Order receipt</title>
                <style>body{font:16px sans-serif;max-width:900px;margin:2rem auto;padding:1rem;color:#222}
                table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.7rem;border-bottom:1px solid #ddd}
                .totals{margin-left:auto;max-width:400px}dt{float:left}dd{text-align:right}h1{font-size:1.6rem}</style>
                </head><body><h1>Nexus order receipt</h1>
                """);
        html.append("<p>Order: <strong>").append(text(order.getOrderNumber())).append("</strong></p>")
                .append("<p>Order ID: ").append(order.getId()).append("</p>")
                .append("<p>Seller: ").append(order.getSellerId()).append("</p>")
                .append("<p>Completed: ").append(text(completedAt.toInstant())).append("</p>");
        if (includeRecipient) appendRecipient(html, order);
        html.append("<h2>Items</h2><table><thead><tr><th>Product</th><th>SKU</th><th>Quantity</th>")
                .append("<th>Unit price</th><th>Amount</th></tr></thead><tbody>");
        order.getItems().forEach(item -> html.append("<tr><td>").append(text(item.getProductName()))
                .append("</td><td>").append(text(item.getSkuCode())).append("</td><td>").append(item.getQuantity())
                .append("</td><td>").append(money(item.getUnitPrice())).append("</td><td>")
                .append(money(item.getTotalPrice())).append("</td></tr>"));
        html.append("</tbody></table><dl class=\"totals\">");
        amount(html, "Subtotal", order.getSubtotal());
        amount(html, "Discount", order.getDiscountAmount());
        amount(html, "Tax", order.getTaxAmount());
        amount(html, "Shipping", order.getShippingFee());
        amount(html, "Total", order.getTotalAmount());
        return html.append("</dl><p>Currency: ").append(text(order.getOrderGroup().getCurrency()))
                .append("</p></body></html>").toString();
    }

    private static void appendRecipient(StringBuilder html, Order order) {
        Object address = order.getShippingInfo() == null ? null : order.getShippingInfo().get("shippingAddress");
        Object recipient = order.getShippingInfo() == null ? null : order.getShippingInfo().get("recipient");
        if (recipient instanceof Map<?, ?> fields && fields.get("address") instanceof Map<?, ?> codes) {
            html.append("<h2>Delivered to</h2><p>").append(text(fields.get("name"))).append("<br>")
                    .append(text(fields.get("phone"))).append("<br>");
            for (String key : new String[]{"line1", "wardCode", "provinceCode"}) {
                html.append(text(codes.get(key))).append("<br>");
            }
            html.append("</p>");
        } else if (address instanceof Map<?, ?> fields) {
            html.append("<h2>Delivered to</h2><p>");
            for (String key : new String[]{"recipientName", "phoneNumber", "addressLine", "ward", "district", "city", "countryCode"}) {
                Object value = fields.get(key);
                if (value != null) html.append(text(value)).append("<br>");
            }
            html.append("</p>");
        }
    }

    private static void amount(StringBuilder html, String label, BigDecimal value) {
        html.append("<dt>").append(label).append("</dt><dd>").append(money(value)).append("</dd>");
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String text(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString(), "UTF-8");
    }
}
