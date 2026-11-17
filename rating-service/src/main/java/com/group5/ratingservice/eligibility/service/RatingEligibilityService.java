package com.group5.ratingservice.eligibility.service;

import com.group5.ratingservice.eligibility.repository.RatingEligibilityRepository;
import com.group5.ratingservice.eligibility.entity.RatingEligibility;
import com.group5.ratingservice.eligibility.event.OrderCompletedPayload;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RatingEligibilityService {

    private final RatingEligibilityRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompletion(UUID sourceEventId, OrderCompletedPayload completion) {
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        Objects.requireNonNull(completion, "completion");
        int inserted = repository.insertIfAbsent(
                completion.orderId(), completion.buyerId(), completion.sellerId(), completion.completedAt(),
                completion.auctionId(), sourceEventId, completion.orderVersion());
        if (inserted == 1) {
            return;
        }

        RatingEligibility existing = repository.findById(completion.orderId()).orElseThrow();
        if (!existing.getBuyerId().equals(completion.buyerId())
                || !existing.getSellerId().equals(completion.sellerId())
                || !existing.getCompletedAt().toInstant().equals(completion.completedAt().toInstant())
                || !Objects.equals(existing.getAuctionId(), completion.auctionId())) {
            throw new IllegalStateException("Conflicting OrderCompleted snapshot for order " + completion.orderId());
        }
    }
}
