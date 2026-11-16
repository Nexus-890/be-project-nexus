package com.group5.ratingservice.rating.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.ratingservice.eligibility.entity.RatingEligibility;
import com.group5.ratingservice.eligibility.repository.RatingEligibilityRepository;
import com.group5.ratingservice.rating.dto.CreateRatingRequest;
import com.group5.ratingservice.rating.event.RatingEventKeyResolver;
import com.group5.ratingservice.rating.repository.RatingRepository;
import com.group5.ratingservice.rating.util.RaterNameMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RatingServiceTest {
    @Test
    void selfRatingIsRejectedBeforeAnyWriteEvenWithAnInvalidProjection() {
        UUID actor = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        RatingEligibility completion = new RatingEligibility();
        completion.setBuyerId(actor);
        completion.setSellerId(actor);
        RatingEligibilityRepository eligibility = mock(RatingEligibilityRepository.class);
        RatingRepository ratings = mock(RatingRepository.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        when(eligibility.findById(orderId)).thenReturn(Optional.of(completion));
        RatingService service = new RatingService(ratings, eligibility, mock(EventEnvelopeFactory.class), outbox);

        assertThatThrownBy(() -> service.create(new CreateRatingRequest(orderId, 5, null),
                new CurrentUser(actor, Set.of("BUYER"), Set.of("REPUTATION.RATE"))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo("RATING_SELF_RATING"));
        verifyNoInteractions(ratings, outbox);
    }

    @ParameterizedTest
    @CsvSource({"Nguyễn Thị Ánh,N***h", "A\u0301nh,Á***h", "A,***", "An,***", "12345,***", "𐐀bc𐐁,𐐀***𐐁"})
    void namesAreMaskedUsingUnicodeLetters(String name, String masked) {
        assertThat(RaterNameMask.mask(name)).isEqualTo(masked);
        assertThat(RaterNameMask.mask(null)).isEqualTo("***");
    }

    @Test
    void keyResolverRejectsAMalformedUserRatedRecipientAndPreservesOtherAggregateKeys() {
        RatingEventKeyResolver resolver = new RatingEventKeyResolver();
        JsonNode payload = JsonMapper.shared().readTree("{\"toUserId\":\"not-a-uuid\"}");
        var event = new EventEnvelope<>(UUID.randomUUID(), "UserRated", UUID.randomUUID().toString(),
                Instant.now(), "trace", payload);
        assertThatThrownBy(() -> resolver.resolve(event)).isInstanceOf(IllegalArgumentException.class);
        var result = new EventEnvelope<>(UUID.randomUUID(), "ReputationScoreUpdated", "user-key",
                Instant.now(), "trace", payload);
        assertThat(resolver.resolve(result)).isEqualTo("user-key");
    }
}
