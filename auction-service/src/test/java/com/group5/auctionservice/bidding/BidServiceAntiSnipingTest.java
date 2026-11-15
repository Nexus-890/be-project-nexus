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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private static final String KEY = "14900000-0000-4000-8000-000000000005";
    private static final BigDecimal AMOUNT = new BigDecimal("1050000.00");
    private static final PlaceBidRequest REQUEST = new PlaceBidRequest(AMOUNT);
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00.123456789Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private AuctionRepository auctions;
    @Mock private BidRepository bids;
    @Mock private OutboxWriter outbox;
    @Mock private EventEnvelopeFactory envelopes;
    @Mock private AntiSnipingPolicy policy;
    @Mock private PlatformTransactionManager transactions;

    private BidService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new BidService(auctions, bids, outbox, envelopes, CLOCK, transactions, policy);
    }

    @Test
    void acceptedBidPersistsBeforeExtensionAndWritesOutboxOnlyAfterPolicy() {
        allowBidAndPersist();
        createEnvelope();

        BidService.PlacementResult result = service.place(AUCTION_ID, KEY, REQUEST, BUYER);

        ArgumentCaptor<Bid> stored = ArgumentCaptor.forClass(Bid.class);
        InOrder order = inOrder(auctions, bids, policy, envelopes, outbox);
        order.verify(bids).findByIdempotencyKey(KEY);
        order.verify(auctions).acceptBid(AUCTION_ID, BUYER.id(), AMOUNT, "***");
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
        assertThat(stored.getValue().getIdempotencyKey()).isEqualTo(KEY);
        assertThat(result.created()).isTrue();
        assertThat(result.response()).isEqualTo(new BidResponse(BID_ID, AMOUNT, BUYER.id()));
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
                .isThrownBy(() -> service.place(AUCTION_ID, KEY,
                        new PlaceBidRequest(new BigDecimal("1049999.99")), BUYER))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo("AUCTION_BID_TOO_LOW");
                    assertThat(error.getProperties()).containsEntry("minimum", AMOUNT);
                });
        verifyNoBidOrExtensionWrites();
    }

    @Test
    void bidPersistenceFailureStopsBeforeExtensionAndOutbox() {
        when(auctions.acceptBid(AUCTION_ID, BUYER.id(), AMOUNT, "***")).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("Bid insert failed");
        when(bids.saveAndFlush(any(Bid.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.place(AUCTION_ID, KEY, REQUEST, BUYER)).isSameAs(failure);

        verifyNoInteractions(policy, envelopes, outbox);
    }

    @Test
    void policyFailureIsPropagatedBeforeEventCreationAndOutbox() {
        allowBidAndPersist();
        IllegalStateException failure = new IllegalStateException("Deadline update failed");
        doThrow(failure).when(policy).extendAcceptedBid(AUCTION_ID, BUYER.id());

        assertThatThrownBy(() -> service.place(AUCTION_ID, KEY, REQUEST, BUYER)).isSameAs(failure);

        verify(bids).saveAndFlush(any(Bid.class));
        verifyNoInteractions(envelopes, outbox);
    }

    @Test
    void noEligibleExtensionDoesNotTurnAnAcceptedBidIntoARefusal() {
        allowBidAndPersist();
        createEnvelope();
        AuctionProperties properties = new AuctionProperties();
        AntiSnipingPolicy realPolicy = new AntiSnipingPolicy(auctions, properties);
        service = new BidService(auctions, bids, outbox, envelopes, CLOCK, transactions, realPolicy);
        when(auctions.extendEndAt(AUCTION_ID, BUYER.id(), 5, 30)).thenReturn(0);

        BidService.PlacementResult result = service.place(AUCTION_ID, KEY, REQUEST, BUYER);

        assertThat(result.created()).isTrue();
        assertThat(result.response()).isEqualTo(new BidResponse(BID_ID, AMOUNT, BUYER.id()));
        verify(auctions).extendEndAt(AUCTION_ID, BUYER.id(), 5, 30);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any(EventEnvelope.class));
    }

    @Test
    void replayOfALastMinuteBidReturnsTheOriginalResponseWithoutRunningThePolicyAgain() {
        Bid previous = new Bid(AUCTION_ID, BUYER.id(), AMOUNT,
                NOW.minusSeconds(30).truncatedTo(ChronoUnit.MICROS), KEY, "***");
        ReflectionTestUtils.setField(previous, "id", BID_ID);
        when(bids.findByIdempotencyKey(KEY)).thenReturn(Optional.of(previous));

        BidService.PlacementResult result = service.place(AUCTION_ID, KEY,
                new PlaceBidRequest(new BigDecimal("1050000")), BUYER);

        assertThat(result.created()).isFalse();
        assertThat(result.response()).isEqualTo(new BidResponse(BID_ID, AMOUNT, BUYER.id()));
        verify(bids).findByIdempotencyKey(KEY);
        verify(bids, never()).saveAndFlush(any(Bid.class));
        verifyNoInteractions(auctions, policy, envelopes, outbox);
    }

    @Test
    void invalidIdempotencyKeyIsRejectedBeforeOpeningTheBidTransaction() {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> service.place(AUCTION_ID, "not-a-uuid", REQUEST, BUYER))
                .satisfies(error -> assertThat(error.getErrorCode())
                        .isEqualTo("AUCTION_INVALID_IDEMPOTENCY_KEY"));

        verifyNoInteractions(transactions, auctions, bids, policy, envelopes, outbox);
    }

    private void allowBidAndPersist() {
        when(auctions.acceptBid(AUCTION_ID, BUYER.id(), AMOUNT, "***")).thenReturn(1);
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

    private void verifyNoBidOrExtensionWrites() {
        // #147 may read the key again after a refused UPDATE, but must not persist or extend.
        verify(bids, never()).saveAndFlush(any(Bid.class));
        verifyNoInteractions(policy, envelopes, outbox);
    }

    private void assertRefused(PlaceBidRequest request, AuctionErrorCode expected) {
        assertThatExceptionOfType(DomainException.class)
                .isThrownBy(() -> service.place(AUCTION_ID, KEY, request, BUYER))
                .satisfies(error -> {
                    assertThat(error.getErrorCode()).isEqualTo(expected.code());
                    assertThat(error.getCode()).isEqualTo(expected.status().value());
                });
        verifyNoBidOrExtensionWrites();
    }
}
