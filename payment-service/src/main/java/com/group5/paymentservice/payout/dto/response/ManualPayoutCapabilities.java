package com.group5.paymentservice.payout.dto.response;

import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import java.time.OffsetDateTime;
import java.util.List;

public record ManualPayoutCapabilities(
        boolean enabled,
        String executionMode,
        boolean automaticJobsEnabled,
        OffsetDateTime serverTime,
        List<ManualPayoutProperties.SourceAccount> sourceAccounts,
        List<String> allowedActions) {}
