package com.group5.paymentservice.payout.service;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SellerPayoutReceiptService {
    private final ManualPayoutService commands;

    public ManualCommandResult confirm(
            CurrentUser actor, UUID payout, UUID attempt, String key, ReceiptCommand body) {
        return commands.execute(actor, payout, attempt, null, "SELLER_RECEIPT", key, body);
    }

    public ManualReceiptView projection(ManualCommandResult result) {
        var a = result.result().currentAttempt();
        var cases = result.result().cases();
        boolean open = cases.stream().anyMatch(c -> !"RESOLVED".equals(c.status()));
        var review =
                cases.stream()
                        .filter(c -> !"RESOLVED".equals(c.status()))
                        .findFirst()
                        .or(() -> cases.stream().findFirst())
                        .orElse(null);
        var actions =
                "SUCCEEDED".equals(a.state())
                        ? "CONFIRMED".equals(a.receiptStatus())
                                ? java.util.List.of("OPEN_DISPUTE")
                                : java.util.List.of("CONFIRM_RECEIPT", "OPEN_DISPUTE")
                        : java.util.List.<String>of();
        return new ManualReceiptView(
                a.id(),
                a.state(),
                a.receiptStatus(),
                a.receiptVersion(),
                open,
                actions,
                review == null
                        ? null
                        : new ManualReceiptView.DisputeSummary(
                                review.id(), review.status(), review.reason()));
    }
}
