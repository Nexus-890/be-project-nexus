package com.group5.ratingservice.rating;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.entity.Rating;
import com.group5.ratingservice.rating.dto.CreateRatingRequest;
import com.group5.ratingservice.rating.dto.RatingResponse;
import com.group5.ratingservice.rating.dto.ReputationResponse;
import com.group5.ratingservice.repository.RatingEligibilityRepository;
import com.group5.ratingservice.repository.RatingRepository;
import com.group5.ratingservice.repository.UserReputationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RatingService {
    private final RatingEligibilityRepository eligibility;
    private final RatingRepository ratings;
    private final UserReputationRepository reputations;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;

    @Transactional
    public RatingResponse create(CreateRatingRequest request, CurrentUser actor) {
        // Both concurrent submissions lock the same completed-order projection before checking uniqueness.
        var order = eligibility.lockByOrderId(request.orderId())
                .orElseThrow(() -> new DomainException(RatingErrorCode.RATING_NOT_ELIGIBLE));
        if (!order.getBuyerId().equals(actor.id()))
            throw new DomainException(RatingErrorCode.RATING_FORBIDDEN);
        if (actor.id().equals(request.toUserId()) || !order.getSellerId().equals(request.toUserId()))
            throw new DomainException(RatingErrorCode.RATING_INVALID_RECIPIENT);
        if (ratings.existsByFromUserIdAndOrderId(actor.id(), request.orderId()))
            throw new DomainException(RatingErrorCode.RATING_ALREADY_EXISTS);
        var rating = new Rating();
        rating.setFromUserId(actor.id());
        rating.setToUserId(order.getSellerId());
        rating.setOrderId(order.getOrderId());
        rating.setAuctionId(order.getAuctionId());
        rating.setScore(request.score().shortValue());
        rating.setComment(request.comment());
        rating = ratings.saveAndFlush(rating);
        outbox.write("Rating", Topics.RATING, envelopes.create("UserRated", rating.getId().toString(),
                new UserRatedPayload(rating.getId(), rating.getOrderId(), rating.getAuctionId(),
                        actor.id(), rating.getToUserId(), rating.getScore(), 0, actor.id(), rating.getCreatedAt().toInstant())));
        return RatingResponse.from(rating);
    }

    @Transactional(readOnly = true)
    public Page<RatingResponse> list(UUID userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw new DomainException(RatingErrorCode.RATING_INVALID_QUERY);
        return ratings.findByToUserId(userId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).map(RatingResponse::from);
    }

    @Transactional(readOnly = true)
    public ReputationResponse reputation(UUID userId) {
        // Read the aggregate owned by the reputation policy; never invent a score from an unverified formula.
        return reputations.findByUserId(userId).map(ReputationResponse::from)
                .orElseThrow(() -> new DomainException(RatingErrorCode.REPUTATION_NOT_FOUND));
    }
}
