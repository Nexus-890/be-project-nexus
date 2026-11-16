package com.group5.ratingservice.rating.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.eligibility.entity.RatingEligibility;
import com.group5.ratingservice.eligibility.repository.RatingEligibilityRepository;
import com.group5.ratingservice.rating.dto.CreateRatingRequest;
import com.group5.ratingservice.rating.dto.RatingResponse;
import com.group5.ratingservice.rating.entity.Rating;
import com.group5.ratingservice.rating.event.UserRatedPayload;
import com.group5.ratingservice.rating.repository.RatingRepository;
import com.group5.ratingservice.rating.util.RaterNameMask;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RatingService {
    private final RatingRepository ratings;
    private final RatingEligibilityRepository eligibility;
    private final EventEnvelopeFactory envelopes;
    private final OutboxWriter outbox;

    @Transactional
    public RatingResponse create(CreateRatingRequest request, CurrentUser user) {
        if (user == null) {
            throw new DomainException(RatingErrorCode.UNAUTHENTICATED);
        }
        RatingEligibility completion = eligibility.findById(request.orderId())
                .orElseThrow(() -> new DomainException(RatingErrorCode.ORDER_NOT_COMPLETED));
        UUID recipient = recipient(completion, user.id());

        Rating rating = new Rating();
        rating.setFromUserId(user.id());
        rating.setToUserId(recipient);
        rating.setOrderId(completion.getOrderId());
        rating.setAuctionId(completion.getAuctionId());
        rating.setScore(request.score().shortValue());
        rating.setComment(request.comment());
        rating.setRaterName(RaterNameMask.mask(user.displayName()));
        try {
            ratings.saveAndFlush(rating);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateRating(exception)) {
                throw new DomainException(RatingErrorCode.ALREADY_EXISTS);
            }
            throw exception;
        }
        outbox.write("Rating", Topics.RATING,
                envelopes.create("UserRated", rating.getId().toString(), UserRatedPayload.from(rating)));
        return RatingResponse.from(rating, user.id());
    }

    @Transactional(readOnly = true)
    public Page<RatingResponse> list(UUID userId, int page, int size, CurrentUser viewer) {
        if (viewer == null) {
            throw new DomainException(RatingErrorCode.UNAUTHENTICATED);
        }
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        }
        Sort order = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        return ratings.findByToUserId(userId, PageRequest.of(page, size, order))
                .map(rating -> RatingResponse.from(rating, viewer.id()));
    }

    private static UUID recipient(RatingEligibility completion, UUID actor) {
        UUID recipient;
        if (actor.equals(completion.getBuyerId())) {
            recipient = completion.getSellerId();
        } else if (actor.equals(completion.getSellerId())) {
            recipient = completion.getBuyerId();
        } else {
            throw new DomainException(RatingErrorCode.NOT_ORDER_PARTICIPANT);
        }
        if (actor.equals(recipient)) {
            throw new DomainException(RatingErrorCode.SELF_RATING);
        }
        return recipient;
    }

    private static boolean isDuplicateRating(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_ratings_from_order".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
