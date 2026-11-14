package com.group5.paymentservice.payment.event.payload;

import java.util.List;
import java.util.UUID;

public record PaymentUnreconciled(UUID sessionId, UUID orderGroupId, UUID transactionId, String providerTransactionId,
                                  UUID buyerId, String amount, String currency, List<UUID> affectedOrderIds,
                                  String reasonCode, String reason, String receivedAt, String providerOccurredAt) {
}
