package com.group5.commerceservice.order;

import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.entity.OrderGroup;
import com.group5.commerceservice.order.entity.OrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderReceiptRendererTest {
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-11-11T13:30:00+07:00");
    private final OrderReceiptRenderer renderer = new OrderReceiptRenderer();

    @ParameterizedTest(name = "{0}")
    @MethodSource("recipientSnapshots")
    void emailOmitsRecipientContactWhileTheOwnerInvoiceRetainsIt(
            String snapshotType, Map<String, Object> shippingInfo, List<String> contactText) {
        Order order = order();
        order.setShippingInfo(shippingInfo);

        String email = renderer.renderForEmail(order, COMPLETED_AT);
        String invoice = renderer.render(order, COMPLETED_AT);

        assertThat(email).doesNotContain("Delivered to").doesNotContain(contactText.toArray(String[]::new));
        assertThat(invoice).contains("Delivered to").contains(contactText.toArray(String[]::new));
        for (String html : List.of(email, invoice)) {
            assertThat(html).startsWith("<!doctype html>")
                    .contains(order.getId().toString(), order.getSellerId().toString(), "NX-RECEIPT-204",
                            "2026-11-11T06:30:00Z", "Phone &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;",
                            "SKU-&amp;-204", "<td>2</td>", "150000.00", "300000.00", "10000.00",
                            "2000.00", "15000.00", "307000.00", "Currency: VND")
                    .doesNotContain("<script>");
        }
        assertThat(order.getShippingInfo()).isEqualTo(shippingInfo);
        assertThat(renderer.render(order, COMPLETED_AT)).isEqualTo(invoice);
    }

    @Test
    void receiptWithoutShippingInfoStillRendersTheStoredFinancialSnapshot() {
        Order order = order();

        assertThat(renderer.renderForEmail(order, COMPLETED_AT))
                .isEqualTo(renderer.render(order, COMPLETED_AT))
                .contains("NX-RECEIPT-204", "307000.00", "2026-11-11T06:30:00Z")
                .doesNotContain("Delivered to");
    }

    private static Stream<Arguments> recipientSnapshots() {
        return Stream.of(
                Arguments.of("canonical recipient", Map.of("recipient", Map.of(
                                "name", "Buyer & Khang", "phone", "0901234567",
                                "address", Map.of("line1", "12 & 14 Private Road", "wardCode", "WARD-PRIVATE",
                                        "provinceCode", "PROVINCE-PRIVATE"))),
                        List.of("Buyer &amp; Khang", "0901234567", "12 &amp; 14 Private Road",
                                "WARD-PRIVATE", "PROVINCE-PRIVATE")),
                Arguments.of("legacy shippingAddress", Map.of("shippingAddress", Map.of(
                                "recipientName", "Buyer & Khang", "phoneNumber", "0907654321",
                                "addressLine", "19 & 21 Private Road", "ward", "Private Ward",
                                "district", "Private District", "city", "Private City", "countryCode", "PRIVATE-COUNTRY")),
                        List.of("Buyer &amp; Khang", "0907654321", "19 &amp; 21 Private Road", "Private Ward",
                                "Private District", "Private City", "PRIVATE-COUNTRY")));
    }

    private static Order order() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setSellerId(UUID.randomUUID());
        order.setOrderNumber("NX-RECEIPT-204");
        order.setSubtotal(new BigDecimal("300000.00"));
        order.setDiscountAmount(new BigDecimal("10000.00"));
        order.setTaxAmount(new BigDecimal("2000.00"));
        order.setShippingFee(new BigDecimal("15000.00"));
        order.setTotalAmount(new BigDecimal("307000.00"));
        OrderGroup group = new OrderGroup();
        group.setCurrency("VND");
        order.setOrderGroup(group);
        OrderItem item = new OrderItem();
        item.setProductName("Phone <script>alert('x')</script>");
        item.setSkuCode("SKU-&-204");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("150000.00"));
        item.setTotalPrice(new BigDecimal("300000.00"));
        order.getItems().add(item);
        return order;
    }
}
