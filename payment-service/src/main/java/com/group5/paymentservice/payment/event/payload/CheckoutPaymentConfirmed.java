package com.group5.paymentservice.payment.event.payload;

import java.util.List;
import java.util.UUID;

/** Manifest toàn bộ checkout đã được cấp tiền; các số tiền là chuỗi thập phân đúng hai chữ số. */
public record CheckoutPaymentConfirmed(UUID sessionId, UUID orderGroupId, UUID transactionId, UUID buyerId,
                                       String currency, String totalAmount, String paidAt, long sessionVersion,
                                       List<Instruction> instructions) {
    /** Phần tiền của một đơn người bán, dùng để Commerce đối chiếu đúng tập đơn và số tiền đã lưu. */
    public record Instruction(UUID instructionId, UUID orderId, String amount) { }
}
