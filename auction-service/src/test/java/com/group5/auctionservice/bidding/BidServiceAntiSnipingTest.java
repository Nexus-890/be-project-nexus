package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.Auction;
import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.bidding.dto.BidResponse;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** #149: unit gate cho hook sau bid được nhận; transaction/SQL được kiểm tra ở integration tests. */
@ExtendWith(MockitoExtension.class)
class BidServiceAntiSnipingTest {
    private static final UUID AUCTION_ID = UUID.fromString("14900000-0000-4000-8000-000000000001");
    private static final UUID BID_ID = UUID.fromString("14900000-0000-4000-8000-000000000002");
    private static final UUID SELLER_ID = UUID.fromString("14900000-0000-4000-8000-000000000003");
    private static final CurrentUser BUYER = new CurrentUser(
            UUID.fromString("14900000-0000-4000-8000-000000000004"), Set.of("BUYER"), Set.of("AUCTION.BID"));
    private static final BigDecimal AMOUNT = new BigDecimal("1050000.00");
    private static final PlaceBidRequest REQUEST = new PlaceBidRequest(AMOUNT);
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private AuctionRepository auctions;
    @Mock private BidRepository bids;
    @Mock private OutboxWriter outbox;
    @Mock private EventEnvelopeFactory envelopes;
    @Mock private AntiSnipingPolicy policy;

    private BidService service;

    @BeforeEach
    void setUp() {
        service = new BidService(auctions, bids, outbox, envelopes, CLOCK, policy);
    }

    @Test
    void acceptedBidPersistsBeforeExtensionAndWritesOutboxOnlyAfterPolicy() {
        allowBidAndPersist();
        createEnvelope();

        BidResponse result = service.place(AUCTION_ID, REQUEST, BUYER);

        ArgumentCaptor<Bid> stored = ArgumentCaptor.forClass(Bid.class);
        InOrder order = inOrder(auctions, bids, policy, envelopes, outbox);
        order.verify(auctions).acceptBid(AUCTION_ID, BUYER.id(), AMOUNT);
        order.verify(bids).saveAndFlush(stored.capture());
        order.verify(policy).extendAcceptedBid(AUCTION_ID, BUYER.id());
        order.verify(envelopes).create(eq("BidPlaced"), eq(AUCTION_ID.toString()), any());
        order.verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any(EventEnvelope.class));
        order.verifyNoMoreInteractions();

        assertThat(stored.getValue().getAuctionId()).isEqualTo(AUCTION_ID);
        assertThat(stored.getValue().getBidderId()).isEqualTo(BUYER.id());
        assertThat(stored.getValue().getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(stored.getValue().getPlacedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
        assertThat(stored.getValue().getStatus()).isEqualTo(Bid.Status.PLACED);
        assertThat(result).isEqualTo(new BidResponse(BID_ID, AMOUNT, BUYER.id()));
    }

    @Test
    void missingAuctionDoesNotEnterAntiSnipingOrWriteBid() {
        when(auctions.findAvailableById(AUCTION_ID)).thenReturn(Optional.empty());

        assertRefused(REQUEST, AuctionErrorCode.AUCTION_NOT_FOUND);
    }

    @Test
    void sellerBidDoesNotEnterAntiSnipingOrWriteBid() {
        Auction auction = availableAuction();
        when(auction.getSellerId()).thenReturn(BUYER.id());

        assertRefused(REQUEST, AuctionErrorCode.AUCTION_SELLER_CANNOT_BID);
    }

    @Test
    void inactiveAuctionDoesNotEnterAntiSnipingOrWriteBid() {
        Auction auction = availableAuction();
        when(auction.getSellerId()).thenReturn(SELLER_ID);
        when(auction.getStatus()).thenReturn(AuctionStatus.ENDED);

        assertRefused(REQUEST, AuctionErrorCode.AUCTION_NOT_ACTIVE);
    }

    @ParameterizedTest(name = "start offset {0}s, end offset {1}s")
    @CsvSource({"1, 3600", "-3600, 0", "-3600, -1"})
    void activeAuctionOutsideDatabaseTimeWindowDoesNotExtend(long startOffset, long endOffset) {
        Auction auction = activeAuction();
        when(auctions.currentTransactionTime()).thenReturn(NOW);
        when(auction.getStartAt()).thenReturn(NOW.plusSeconds(startOffset));
        // Before-start refusal short-circuits before reading endAt.
        if (startOffset <= 0) {
            when(auction.getEndAt()).thenReturn(NOW.plusSeconds(endOffset));
        }

        assertRefused(REQUEST, AuctionErrorCode.AUCTION_NOT_ACTIVE);
    }

    @Test
    void tooLowBidInsideLastTwoMinutesDoesNotExtendOrWriteAnything() {
        Auction auction = activeAuction();
        when(auctions.currentTransactionTime()).thenReturn(NOW);
        when(auction.getStartAt()).thenReturn(NOW.minusSeconds(3600));
        when(auction.getEndAt()).thenReturn(NOW.plusSeconds(120));
        when(auction.getCurrentPrice()).thenReturn(new BigDecimal("1000000.00"));
        when(auction.getBidIncrement()).thenReturn(new BigDecimal("50000.00"));

        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> service.place(AUCTION_ID,
                        new PlaceBidRequest(new BigDecimal("1049999.99")), BUYER))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo("AUCTION_BID_TOO_LOW");
                    assertThat(error.getProperties()).containsEntry("minimum", AMOUNT);
                });
        verifyNoInteractions(policy, bids, envelopes, outbox);
    }

    @Test
    void bidPersistenceFailureStopsBeforeExtensionAndOutbox() {
        when(auctions.acceptBid(AUCTION_ID, BUYER.id(), AMOUNT)).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("Bid insert failed");
        when(bids.saveAndFlush(any(Bid.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.place(AUCTION_ID, REQUEST, BUYER)).isSameAs(failure);

        verifyNoInteractions(policy, envelopes, outbox);
    }

    @Test
    void policyFailureIsPropagatedBeforeEventCreationAndOutbox() {
        allowBidAndPersist();
        IllegalStateException failure = new IllegalStateException("Deadline update failed");
        doThrow(failure).when(policy).extendAcceptedBid(AUCTION_ID, BUYER.id());

        assertThatThrownBy(() -> service.place(AUCTION_ID, REQUEST, BUYER)).isSameAs(failure);

        verify(bids).saveAndFlush(any(Bid.class));
        verifyNoInteractions(envelopes, outbox);
    }

    @Test
    void noEligibleExtensionDoesNotTurnAnAcceptedBidIntoARefusal() {
        allowBidAndPersist();
        createEnvelope();
        AuctionProperties properties = new AuctionProperties();
        AntiSnipingPolicy realPolicy = new AntiSnipingPolicy(auctions, properties);
        service = new BidService(auctions, bids, outbox, envelopes, CLOCK, realPolicy);
        when(auctions.extendEndAt(AUCTION_ID, BUYER.id(), 5, 30)).thenReturn(0);

        BidResponse result = service.place(AUCTION_ID, REQUEST, BUYER);

        assertThat(result).isEqualTo(new BidResponse(BID_ID, AMOUNT, BUYER.id()));
        verify(auctions).extendEndAt(AUCTION_ID, BUYER.id(), 5, 30);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any(EventEnvelope.class));
    }

    private void allowBidAndPersist() {
        when(auctions.acceptBid(AUCTION_ID, BUYER.id(), AMOUNT)).thenReturn(1);
        when(bids.saveAndFlush(any(Bid.class))).thenAnswer(invocation -> {
            Bid bid = invocation.getArgument(0);
            ReflectionTestUtils.setField(bid, "id", BID_ID);
            return bid;
        });
    }

    private void createEnvelope() {
        when(envelopes.create(eq("BidPlaced"), eq(AUCTION_ID.toString()), any()))
                .thenAnswer(invocation -> new EventEnvelope<>(UUID.randomUUID(), "BidPlaced",
                        AUCTION_ID.toString(), NOW, "unit-test-149", invocation.getArgument(2)));
    }

    private Auction availableAuction() {
        Auction auction = mock(Auction.class);
        when(auctions.findAvailableById(AUCTION_ID)).thenReturn(Optional.of(auction));
        return auction;
    }

    private Auction activeAuction() {
        Auction auction = availableAuction();
        when(auction.getSellerId()).thenReturn(SELLER_ID);
        when(auction.getStatus()).thenReturn(AuctionStatus.ACTIVE);
        return auction;
    }

    private void assertRefused(PlaceBidRequest request, AuctionErrorCode expected) {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> service.place(AUCTION_ID, request, BUYER))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(expected.code());
                    assertThat(error.getCode()).isEqualTo(expected.status().value());
                });
        verifyNoInteractions(policy, bids, envelopes, outbox);
    }
}
