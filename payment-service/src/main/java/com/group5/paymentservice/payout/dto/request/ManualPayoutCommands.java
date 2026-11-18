package com.group5.paymentservice.payout.dto.request;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Named command contracts: actor identity always comes from the authenticated principal. */
public final class ManualPayoutCommands {
    private ManualPayoutCommands() {}

    public record ClaimCommand(@NotNull @PositiveOrZero Long expectedPayoutVersion) {}

    public record PayoutVersionCommand(
            @NotNull @PositiveOrZero Long expectedPayoutVersion, @Size(max = 1000) String reason) {}

    public record AttemptVersionCommand(
            @NotNull @PositiveOrZero Long expectedAttemptVersion,
            @Size(max = 1000) String reason) {}

    public record ConfirmationCommand(
            @NotNull @PositiveOrZero Long expectedAttemptVersion,
            @NotBlank @Size(max = 80) String sourceAccountId,
            @NotNull @Pattern(regexp = "[0-9]{1,17}\\.[0-9]{2}") String amount,
            @NotBlank @Size(max = 120) String bankTransferReference,
            @NotNull OffsetDateTime transferredAt,
            @NotEmpty @Size(max = 3) List<@NotNull UUID> evidenceIds) {}

    public record ProblemCommand(
            @NotNull @PositiveOrZero Long expectedAttemptVersion,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 3) List<@NotNull UUID> evidenceIds) {}

    public record ResolutionCommand(
            @NotNull @PositiveOrZero Long expectedPayoutVersion,
            @NotNull @PositiveOrZero Long expectedAttemptVersion,
            @NotNull @PositiveOrZero Long expectedCaseVersion,
            @NotNull UUID caseId,
            @NotNull ResolutionOutcome outcome,
            @NotBlank @Size(max = 1000) String reason,
            @NotEmpty @Size(max = 3) List<@NotNull UUID> evidenceIds,
            @Size(max = 80) String sourceAccountId,
            @Pattern(regexp = "[0-9]{1,17}\\.[0-9]{2}") String amount,
            @Size(max = 120) String bankTransferReference,
            OffsetDateTime transferredAt) {}

    public enum ResolutionOutcome {
        CONFIRMED_SENT,
        CONFIRMED_NOT_SENT,
        CONFIRMED_RETURNED,
        CONFIRMATION_RECORDED_IN_ERROR
    }

    public record ReviewCommand(
            @NotNull @PositiveOrZero Long expectedAttemptVersion,
            @NotNull @PositiveOrZero Long expectedCaseVersion,
            @NotNull ReviewAction action,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 3) List<@NotNull UUID> evidenceIds) {}

    public enum ReviewAction {
        REQUEST_INFORMATION,
        TRANSFER_EVIDENCE_PROVIDED
    }

    public record ReceiptCommand(@NotNull @PositiveOrZero Long expectedReceiptVersion) {}

    public record DisputeCommand(
            @NotNull @PositiveOrZero Long expectedReceiptVersion,
            @NotNull DisputeReason reason,
            @NotBlank @Size(max = 1000) String explanation) {}

    public enum DisputeReason {
        NOT_RECEIVED,
        INCORRECT_AMOUNT,
        CONFIRMATION_MISTAKE
    }
}
