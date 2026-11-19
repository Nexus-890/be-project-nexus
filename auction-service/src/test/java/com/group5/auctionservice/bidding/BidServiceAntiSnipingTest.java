package com.group5.auctionservice.bidding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.group5.auctionservice.auction.*;
import com.group5.auctionservice.bidding.dto.PlaceBidRequest;
import com.group5.auctionservice.reputation.ReputationGuard;
import com.group5.common_events.EventEnvelope;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Unit gate: anti-sniping nằm sau conditional UPDATE của consumer, không còn chạy tại POST. */
@ExtendWith(MockitoExtension.class)
class BidServiceAntiSnipingTest {
    private static final UUID AUCTION = UUID.randomUUID(), BID = UUID.randomUUID();
    private static final CurrentUser BUYER =
            new CurrentUser(UUID.randomUUID(), Set.of("BUYER"), Set.of("AUCTION.BID"));
    private static final Instant REQUESTED = Instant.parse("2026-11-16T02:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1050000.00");
    @Mock AuctionRepository auctions;
    @Mock BidRepository bids;
    @Mock OutboxWriter outbox;
    @Mock EventEnvelopeFactory envelopes;
    @Mock AntiSnipingPolicy policy;
    @Mock PlatformTransactionManager transactions;
    @Mock ReputationGuard reputation;
    private BidService service;

    /**
     * Dùng transaction manager nhẹ cho unit; rollback thật được kiểm tra ở integration PostgreSQL.
     */
    @BeforeEach
    void setup() {
        lenient()
                .when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service =
                new BidService(
                        auctions, bids, outbox, envelopes, Clock.systemUTC(), transactions, policy, reputation);
    }

    /** Consumer lấy khóa xử lý trước, update giá rồi mới gia hạn bằng requestedAt. */
    @Test
    void appliesConditionalPriceThenExtendsUsingRequestedAtBeforeWritingPlacedEvent() {
        Bid bid = queued();
        allowQueue(bid);
        when(auctions.acceptQueuedBid(AUCTION, BUYER.id(), AMOUNT, "***", REQUESTED)).thenReturn(1);
        envelope("BidPlaced");
        service.apply(AUCTION, BID);
        InOrder order = inOrder(bids, auctions, policy, outbox);
        order.verify(bids).lockProcessing(AUCTION);
        order.verify(bids).findById(BID);
        order.verify(bids).lockQueuedThrough(AUCTION, REQUESTED, BID);
        order.verify(auctions).currentWallTime();
        order.verify(auctions).acceptQueuedBid(AUCTION, BUYER.id(), AMOUNT, "***", REQUESTED);
        order.verify(policy).extendAcceptedBid(AUCTION, BUYER.id(), REQUESTED);
        order.verify(bids).save(bid);
        order.verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any());
        assertThat(bid.getStatus()).isEqualTo(Bid.Status.PLACED);
        assertThat(bid.getPlacedAt()).isEqualTo(REQUESTED);
    }

    /** Giá thấp thành REJECTED; không được gia hạn và không phát BidPlaced. */
    @Test
    void rejectedBidDoesNotExtendOrPublishPlacedEvent() {
        Bid bid = queued();
        allowQueue(bid);
        Auction auction = mock(Auction.class);
        when(auctions.findAvailableById(AUCTION)).thenReturn(Optional.of(auction));
        when(auction.getStatus()).thenReturn(AuctionStatus.ACTIVE);
        when(auction.getStartAt()).thenReturn(REQUESTED.minusSeconds(10));
        when(auction.getEndAt()).thenReturn(REQUESTED.plusSeconds(1));
        when(auction.getSellerId()).thenReturn(UUID.randomUUID());
        when(auction.getCurrentPrice()).thenReturn(AMOUNT);
        when(auction.getBidIncrement()).thenReturn(new BigDecimal("50000"));
        service.apply(AUCTION, BID);
        assertThat(bid.getStatus()).isEqualTo(Bid.Status.REJECTED);
        assertThat(bid.getRejectReason()).isEqualTo("AUCTION_BID_TOO_LOW");
        assertThat(bid.getRejectMinimum()).isEqualByComparingTo("1100000");
        verifyNoInteractions(policy, outbox, envelopes);
    }

    /**
     * Lỗi policy đi lên transaction để caller rollback giá và marker, không gửi event nửa chừng.
     */
    @Test
    void policyFailureStopsBeforeResultAndEvent() {
        Bid bid = queued();
        allowQueue(bid);
        when(auctions.acceptQueuedBid(AUCTION, BUYER.id(), AMOUNT, "***", REQUESTED)).thenReturn(1);
        doThrow(new IllegalStateException("extension failed"))
                .when(policy)
                .extendAcceptedBid(AUCTION, BUYER.id(), REQUESTED);
        assertThatThrownBy(() -> service.apply(AUCTION, BID)).hasMessage("extension failed");
        verifyNoInteractions(outbox, envelopes);
        verify(bids, never()).save(any());
    }

    /** Request đã xử lý không được áp dụng giá hay anti-sniping lại dù eventId khác. */
    @Test
    void alreadyProcessedRequestDoesNotRunPolicyAgain() {
        Bid bid = queued();
        bid.placed(REQUESTED.plusSeconds(1));
        when(bids.findById(BID)).thenReturn(Optional.of(bid));
        service.apply(AUCTION, BID);
        verifyNoInteractions(policy, outbox, envelopes);
        verifyNoInteractions(auctions);
        verify(bids, never()).lockQueuedThrough(any(), any(), any());
    }

    /** POST chỉ kiểm tra metadata và ghi QUEUED/command; tuyệt đối không update giá/gia hạn. */
    @Test
    void postQueuesWithoutApplyingPriceOrPolicy() {
        Auction auction = mock(Auction.class);
        when(auctions.findAvailableById(AUCTION)).thenReturn(Optional.of(auction));
        when(auctions.currentWallTime()).thenReturn(REQUESTED);
        when(auction.getSellerId()).thenReturn(UUID.randomUUID());
        when(auction.getStatus()).thenReturn(AuctionStatus.ACTIVE);
        when(auction.getStartAt()).thenReturn(REQUESTED.minusSeconds(10));
        when(auction.getEndAt()).thenReturn(REQUESTED.plusSeconds(1));
        when(bids.saveAndFlush(any(Bid.class)))
                .thenAnswer(
                        i -> {
                            Bid bid = i.getArgument(0);
                            ReflectionTestUtils.setField(bid, "id", BID);
                            return bid;
                        });
        envelope("BidRequested");
        var result =
                service.place(
                        AUCTION, UUID.randomUUID().toString(), new PlaceBidRequest(AMOUNT), BUYER);
        assertThat(result.response().status()).isEqualTo("QUEUED");
        assertThat(result.response().requestedAt()).isEqualTo(REQUESTED);
        verify(auctions).lockBidAdmission(AUCTION);
        verify(auctions, never()).acceptQueuedBid(any(), any(), any(), any(), any());
        verifyNoInteractions(policy);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION_BIDS), any());
    }

    /** Key sai bị loại trước khi mở transaction và đọc metadata. */
    @Test
    void malformedKeyFailsBeforeTransaction() {
        assertThatThrownBy(
                        () -> service.place(AUCTION, "bad-key", new PlaceBidRequest(AMOUNT), BUYER))
                .extracting("errorCode")
                .isEqualTo("AUCTION_INVALID_IDEMPOTENCY_KEY");
        verifyNoInteractions(transactions, auctions, bids, policy, envelopes, outbox, reputation);
    }

    /** Fixture QUEUED giữ requestedAt độc lập với thời gian consumer. */
    private Bid queued() {
        Bid bid =
                Bid.queued(
                        AUCTION,
                        BUYER.id(),
                        AMOUNT,
                        REQUESTED,
                        UUID.randomUUID().toString(),
                        "***");
        ReflectionTestUtils.setField(bid, "id", BID);
        return bid;
    }

    @Test
    void changingLeaderWritesOutbidAlongsideAcceptedBid() {
        Bid bid = queued(); allowQueue(bid);
        UUID oldLeader = UUID.randomUUID();
        when(auctions.lockLeaderForOutbid(AUCTION)).thenReturn(Optional.of(oldLeader));
        when(auctions.acceptQueuedBid(AUCTION, BUYER.id(), AMOUNT, "***", REQUESTED)).thenReturn(1);
        envelope("BidPlaced"); envelope("BidOutbid");
        service.apply(AUCTION, BID);
        ArgumentCaptor<EventEnvelope<?>> events = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox, times(2)).write(eq("Auction"), eq(Topics.AUCTION), events.capture());
        assertThat(events.getAllValues()).extracting(EventEnvelope::eventType).containsExactly("BidPlaced", "BidOutbid");
        assertThat(ReflectionTestUtils.getField(events.getAllValues().getLast().payload(), "outbidUserId")).isEqualTo(oldLeader);
    }

    @Test
    void sameLeaderIncreasingOwnBidDoesNotReceiveOutbid() {
        Bid bid = queued(); allowQueue(bid);
        when(auctions.lockLeaderForOutbid(AUCTION)).thenReturn(Optional.of(BUYER.id()));
        when(auctions.acceptQueuedBid(AUCTION, BUYER.id(), AMOUNT, "***", REQUESTED)).thenReturn(1);
        envelope("BidPlaced");
        service.apply(AUCTION, BID);
        verify(outbox).write(eq("Auction"), eq(Topics.AUCTION), any());
        verify(envelopes, never()).create(eq("BidOutbid"), anyString(), any());
    }

    /** Danh sách queue thật được test DB; unit chỉ chỉ định một request cần áp dụng. */
    private void allowQueue(Bid bid) {
        when(bids.findById(BID)).thenReturn(Optional.of(bid));
        when(bids.lockQueuedThrough(AUCTION, REQUESTED, BID)).thenReturn(List.of(bid));
        when(auctions.currentWallTime()).thenReturn(REQUESTED.plusSeconds(30));
    }

    /** Envelope mock vẫn giữ type/payload để xác nhận đúng topic và thời điểm publish. */
    private void envelope(String type) {
        when(envelopes.create(eq(type), eq(AUCTION.toString()), any()))
                .thenAnswer(
                        i ->
                                new EventEnvelope<>(
                                        UUID.randomUUID(),
                                        type,
                                        AUCTION.toString(),
                                        REQUESTED,
                                        "queue-unit",
                                        i.getArgument(2)));
    }
}
