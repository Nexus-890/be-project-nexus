package com.group5.commerceservice.promotion;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MegaSaleEvent(UUID id, String name, String description, String bannerUrl,
                            OffsetDateTime registrationStartAt, OffsetDateTime registrationEndAt,
                            OffsetDateTime startAt, OffsetDateTime endAt, Status status,
                            boolean registrationOpenRequested, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public enum Status { DRAFT, REGISTRATION_OPEN, REGISTRATION_CLOSED, ACTIVE, ENDED, CANCELED }
    public enum Action { OPEN, CANCEL }
    public enum SellerStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }
    public enum ReviewAction { APPROVED, REJECTED }

    public record Write(@NotBlank @Size(max = 200) String name, String description,
                        @Size(max = 500) String bannerUrl,
                        @NotNull OffsetDateTime registrationStartAt, @NotNull OffsetDateTime registrationEndAt,
                        @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime endAt) { }

    public record ChangeStatus(@NotNull Action status) { }
    public record Review(@NotNull ReviewAction status, @Size(max = 2000) String reviewNote) { }

    public record Seller(UUID id, UUID eventId, UUID sellerId, SellerStatus status,
                         OffsetDateTime registeredAt, UUID reviewedBy, OffsetDateTime reviewedAt,
                         String reviewNote, OffsetDateTime withdrawnAt) { }

    boolean acceptingRegistrations(OffsetDateTime now) {
        return status == Status.REGISTRATION_OPEN && !now.isBefore(registrationStartAt)
                && now.isBefore(registrationEndAt);
    }
}
