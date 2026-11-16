package com.group5.paymentservice.payout;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexus.payout.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPayoutGateway implements PayoutGateway {
    private final PayoutProperties properties;

    public String provider() {
        return "sandbox";
    }

    public boolean simulated() {
        return true;
    }

    public Result send(Transfer transfer) {
        return outcome(transfer.key(), false);
    }

    public Result lookup(String key) {
        return outcome(key, true);
    }

    // Cùng key luôn có cùng mã giao dịch giả lập để đối soát được cả sau khi khởi động lại.
    private Result outcome(String key, boolean lookup) {
        String reference = "sandbox-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        return switch (properties.getSandboxOutcome()) {
            case "FAILURE" -> new Result(State.FAILED, reference, "SIMULATED_FAILURE");
            case "TIMEOUT" -> new Result(lookup ? State.SUCCEEDED : State.UNKNOWN, reference, "SIMULATED_TIMEOUT_RECONCILED");
            case "ACCEPTED" -> new Result(lookup ? State.SUCCEEDED : State.ACCEPTED, reference, "SIMULATED_ACCEPTED");
            case "RETURN" -> new Result(lookup ? State.RETURNED : State.SUCCEEDED, reference, "SIMULATED_RETURN");
            default -> new Result(State.SUCCEEDED, reference, "SIMULATED_SUCCESS");
        };
    }
}
