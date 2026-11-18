package com.group5.auctionservice.settlement;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStateMachine;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.IdempotentConsumer;
import com.group5.common_outbox.OutboxWriter;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test không cần DB/Kafka; transaction và rollback thực được kiểm ở SettlementStatusUpdaterTest. */
class SettlementStatusUpdaterUnitTest {
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Instant ENVELOPE_TIME = Instant.parse("2031-01-01T12:00:00Z");
    private final IdempotentConsumer idempotent = mock(IdempotentConsumer.class);
    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
    private final SettlementStatusUpdater updater = new SettlementStatusUpdater(idempotent, auctions,
            new AuctionStateMachine(), outbox,
            new EventEnvelopeFactory(tracer, Clock.fixed(ENVELOPE_TIME, ZoneOffset.UTC)), JSON, tracer,
            mock(org.springframework.jdbc.core.JdbcTemplate.class), mock(com.group5.auctionservice.offer.AuctionPaymentTimeoutHandler.class));

    @BeforeEach
    void executeClaimedCallback() {
        when(tracer.currentSpan().context().traceId()).thenReturn("172-unit-settlement");
        doAnswer(invocation -> {
            EventEnvelope<JsonNode> envelope = invocation.getArgument(1);
            Consumer<EventEnvelope<JsonNode>> callback = invocation.getArgument(2);
            callback.accept(envelope);
            return true;
        }).when(idempotent).handle(eq(SettlementStatusUpdater.CONSUMER), any(), any());
        clearInvocations(tracer);
    }

    @Test
    @DisplayName("WON → SETTLED lưu orderId và phát đúng snapshot, không đổi winner/giá")
    void wonAuctionTransitionsThroughRealStateMachineAndPublishesStoredSettlement() {
        Auction auction = auction(AuctionStatus.WON);
        UUID orderId = UUID.randomUUID();
        UUID winnerBefore = auction.getWinnerId();
        BigDecimal priceBefore = auction.getFinalPrice();
        Instant endBefore = auction.getEndAt();
        when(auctions.findForUpdateByIdAndDeletedAtIsNull(auction.getId())).thenReturn(Optional.of(auction));
        Instant before = Instant.now().minusMillis(1);

        updater.receive(created(auction.getId(), orderId));

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SETTLED);
        assertThat(auction.getOrderId()).isEqualTo(orderId);
        assertThat(auction.getSettledAt()).isBetween(before, Instant.now());
        assertThat(auction.getSettledAt().getNano() % 1_000).isZero();
        assertThat(auction.getWinnerId()).isEqualTo(winnerBefore);
        assertThat(auction.getFinalPrice()).isEqualByComparingTo(priceBefore);
        assertThat(auction.getEndAt()).isEqualTo(endBefore);
        verify(auctions).saveAndFlush(auction);

        ArgumentCaptor<EventEnvelope<?>> sent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), sent.capture());
        EventEnvelope<?> event = sent.getValue();
        assertThat(event.eventType()).isEqualTo("AuctionSettled");
        assertThat(event.aggregateId()).isEqualTo(auction.getId().toString());
        assertThat(event.traceId()).isEqualTo("172-unit-settlement");
        assertThat(event.payload()).isEqualTo(new SettlementStatusUpdater.Settled(
                auction.getId(), orderId, auction.getSettledAt()));
    }

    @ParameterizedTest(name = "{0}: không đổi trạng thái, ghi một WARN")
    @EnumSource(value = AuctionStatus.class, names = "WON", mode = EnumSource.Mode.EXCLUDE)
    void nonWonAuctionIsUnchangedAndWarnedOnce(AuctionStatus status) {
        Auction auction = auction(status);
        when(auctions.findForUpdateByIdAndDeletedAtIsNull(auction.getId())).thenReturn(Optional.of(auction));
        Logger logger = (Logger) LoggerFactory.getLogger(SettlementStatusUpdater.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            updater.receive(created(auction.getId(), UUID.randomUUID()));
            assertThat(captured.list).filteredOn(event -> event.getLevel() == Level.WARN)
                    .singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                            .contains(auction.getId().toString(), status.name()));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
        assertThat(auction.getStatus()).isEqualTo(status);
        assertThat(auction.getOrderId()).isNull();
        assertThat(auction.getSettledAt()).isNull();
        verify(auctions, times(0)).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Auction không tồn tại không được tạo/mở lại từ OrderCreated")
    void missingAuctionProducesNoStateWriteOrSettlementEvent() {
        UUID auctionId = UUID.randomUUID();
        when(auctions.findForUpdateByIdAndDeletedAtIsNull(auctionId)).thenReturn(Optional.empty());

        updater.receive(created(auctionId, UUID.randomUUID()));

        verify(auctions, times(0)).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Marker đã xử lý chặn callback lần hai, không phát settlement lần hai")
    void alreadyClaimedEventDoesNotExecuteBusinessCallbackTwice() {
        Auction auction = auction(AuctionStatus.WON);
        when(auctions.findForUpdateByIdAndDeletedAtIsNull(auction.getId())).thenReturn(Optional.of(auction));
        AtomicBoolean firstClaim = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!firstClaim.getAndSet(false)) return false;
            EventEnvelope<JsonNode> envelope = invocation.getArgument(1);
            Consumer<EventEnvelope<JsonNode>> callback = invocation.getArgument(2);
            callback.accept(envelope);
            return true;
        }).when(idempotent).handle(eq(SettlementStatusUpdater.CONSUMER), any(), any());
        var record = created(auction.getId(), UUID.randomUUID());

        updater.receive(record);
        Instant settledAt = auction.getSettledAt();
        updater.receive(record);

        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SETTLED);
        assertThat(auction.getSettledAt()).isEqualTo(settledAt);
        verify(auctions).findForUpdateByIdAndDeletedAtIsNull(auction.getId());
        verify(auctions).saveAndFlush(auction);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any());
    }

    @ParameterizedTest(name = "Bỏ qua {0} trước khi claim event")
    @ValueSource(strings = {"STANDARD", "UNRELATED_EVENT"})
    void unrelatedEventsNeverClaimOrReadAnAuction(String kind) {
        UUID orderId = UUID.randomUUID();
        String type = "UNRELATED_EVENT".equals(kind) ? "OrderPaid" : "OrderCreated";
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), type, orderId.toString(), ENVELOPE_TIME,
                "source-trace", JSON.valueToTree(Map.of("orderType", kind)));

        updater.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, orderId.toString(), event));

        verifyNoInteractions(idempotent, auctions, outbox, tracer);
    }

    @ParameterizedTest(name = "Từ chối identity sai: {0}")
    @ValueSource(strings = {"key", "aggregate", "orderId", "referenceId"})
    void invalidOrderIdentityIsRejectedBeforeClaimOrStateAccess(String invalid) {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderType", "AUCTION");
        payload.put("orderId", "orderId".equals(invalid) ? null : orderId);
        payload.put("referenceId", "referenceId".equals(invalid) ? null : UUID.randomUUID());
        String aggregate = "aggregate".equals(invalid) ? UUID.randomUUID().toString() : orderId.toString();
        String key = "key".equals(invalid) ? UUID.randomUUID().toString() : aggregate;
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", aggregate, ENVELOPE_TIME,
                "source-trace", JSON.valueToTree(payload));

        assertThatThrownBy(() -> updater.receive(new ConsumerRecord<>(Topics.COMMERCE, 0, 0, key, event)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity mismatch");

        verifyNoInteractions(idempotent, auctions, outbox, tracer);
    }

    @Test
    @DisplayName("Lỗi outbox phải thoát khỏi callback để transaction thực rollback marker và trạng thái")
    void outboxFailurePropagatesToTheIdempotentConsumerTransaction() {
        Auction auction = auction(AuctionStatus.WON);
        when(auctions.findForUpdateByIdAndDeletedAtIsNull(auction.getId())).thenReturn(Optional.of(auction));
        var failure = new IllegalStateException("outbox unavailable");
        doThrow(failure).when(outbox).write(eq("Auction"), eq(Topics.AUCTION), any());

        assertThatThrownBy(() -> updater.receive(created(auction.getId(), UUID.randomUUID())))
                .isSameAs(failure);

        verify(auctions).saveAndFlush(auction);
        // Mock callback không rollback entity trong RAM; integration test kiểm DB và processed marker.
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any());
    }

    private Auction auction(AuctionStatus status) {
        Auction auction = BeanUtils.instantiateClass(Auction.class);
        ReflectionTestUtils.setField(auction, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(auction, "status", status);
        ReflectionTestUtils.setField(auction, "winnerId", UUID.randomUUID());
        ReflectionTestUtils.setField(auction, "finalPrice", new BigDecimal("1500000.00"));
        ReflectionTestUtils.setField(auction, "endAt", Instant.parse("2031-01-01T00:00:00Z"));
        return auction;
    }

    private ConsumerRecord<String, EventEnvelope<JsonNode>> created(UUID auctionId, UUID orderId) {
        var payload = Map.of("orderId", orderId, "orderType", "AUCTION", "referenceId", auctionId,
                "buyerId", UUID.randomUUID(), "productIds", List.of(UUID.randomUUID()));
        EventEnvelope<JsonNode> event = new EventEnvelope<>(UUID.randomUUID(), "OrderCreated", orderId.toString(), ENVELOPE_TIME,
                "source-trace", JSON.valueToTree(payload));
        return new ConsumerRecord<>(Topics.COMMERCE, 0, 0, orderId.toString(), event);
    }
}
