package com.group5.ratingservice.reputation.dto;

import java.util.UUID;

public record AdjustmentResult(UUID operationId, ReputationSnapshot reputation, boolean replayed) {
}
