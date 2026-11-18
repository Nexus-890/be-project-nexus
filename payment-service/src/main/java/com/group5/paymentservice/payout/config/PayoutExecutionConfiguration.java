package com.group5.paymentservice.payout.config;

import org.springframework.stereotype.Component;

/** Reject incompatible execution settings before any scheduled payout work starts. */
@Component
public class PayoutExecutionConfiguration {
    public PayoutExecutionConfiguration(PayoutProperties payout, ManualPayoutProperties manual) {
        validate(payout.getExecutionMode(), payout.isJobsEnabled(), manual.isEnabled());
    }

    static void validate(String mode, boolean jobsEnabled, boolean manualEnabled) {
        if (jobsEnabled && manualEnabled) {
            throw new IllegalStateException(
                    "Automatic payout jobs and manual payout intake cannot both be enabled");
        }
        if (jobsEnabled && !"AUTO".equals(mode)) {
            throw new IllegalStateException("Automatic payout jobs require execution-mode=AUTO");
        }
        if (manualEnabled && !"MANUAL".equals(mode)) {
            throw new IllegalStateException("Manual payout intake requires execution-mode=MANUAL");
        }
    }
}
