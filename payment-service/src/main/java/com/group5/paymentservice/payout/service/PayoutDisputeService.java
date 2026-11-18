package com.group5.paymentservice.payout.service;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.DisputeCommand;
import com.group5.paymentservice.payout.dto.response.ManualCommandResult;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PayoutDisputeService {
    private final ManualPayoutService commands;

    public ManualCommandResult open(
            CurrentUser actor, UUID payout, UUID attempt, String key, DisputeCommand body) {
        return commands.execute(actor, payout, attempt, null, "SELLER_DISPUTE", key, body);
    }
}
