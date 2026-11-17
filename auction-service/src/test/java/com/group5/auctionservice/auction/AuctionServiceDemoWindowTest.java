package com.group5.auctionservice.auction;

import com.group5.auctionservice.auction.dto.CreateAuctionRequest;
import com.group5.auctionservice.bidding.BidRepository;
import com.group5.auctionservice.reputation.EligibilityAction;
import com.group5.auctionservice.reputation.ReputationGuard;
import com.group5.auctionservice.shared.config.AuctionProperties;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Rule tạo auction thật với Clock cố định; các service ngoài và persistence được mock. */
@ExtendWith(MockitoExtension.class)
class AuctionServiceDemoWindowTest {
    private static final Instant NOW = Instant.parse("2026-11-17T02:00:00Z");
    private static final UUID PRODUCT = UUID.fromString("69000000-0000-4000-8000-000000000204");
    private static final UUID SKU = UUID.fromString("15800000-0000-4000-8000-000000000001");
    private static final UUID SELLER = UUID.fromString("68000000-0000-4000-8000-000000000002");
    private static final String IMAGE = "https://example.test/demo-shirt.jpg";
    private static final CurrentUser ACTOR = new CurrentUser(SELLER, Set.of("SELLER"), Set.of("AUCTION.CREATE"));

    @Mock AuctionRepository auctions;
    @Mock BidRepository bids;
    @Spy AuctionProperties properties = new AuctionProperties();
    @Mock Clock clock;
    @Mock AuctionStateMachine stateMachine;
    @Mock AuctionCatalog catalog;
    @Mock AuctionInventoryClient inventory;
    @Mock AuctionCreation creation;
    @Mock OutboxWriter outbox;
    @Mock EventEnvelopeFactory envelopes;
    @Mock ReputationGuard reputation;
    // Inject theo kiểu dependency để dùng được cả thứ tự constructor cũ trên nhánh MR và checkout tích hợp.
    @InjectMocks AuctionService service;

    @BeforeEach
    void fixedTime() {
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void defaultSixtyMinuteRuleRejectsTheThreeMinuteDemoBeforeExternalCalls() {
        assertThat(properties.getMinDurationMinutes()).isEqualTo(60);
        assertRejected(request(180), "AUCTION_DURATION_TOO_SHORT");
        verifyNoInteractions(catalog, inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void stagingOneMinuteRuleAcceptsThreeMinutesWithTheExactRequestedDeadline() {
        properties.setMinDurationMinutes(1);
        validProductAndStock();

        var result = service.create(request(180), ACTOR);

        assertThat(result.startAt()).isEqualTo(NOW);
        assertThat(result.endAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(result.originalEndAt()).isEqualTo(result.endAt());
        assertThat(result.status()).isEqualTo(AuctionStatus.CREATED);
        assertThat(result.sellerId()).isEqualTo(SELLER);
        verify(reputation).requireEligible(SELLER, EligibilityAction.SELL);
        verify(creation).save(any(Auction.class));
    }

    @Test
    void stagingAcceptsExactlyOneMinute() {
        properties.setMinDurationMinutes(1);
        validProductAndStock();
        assertThat(service.create(request(60), ACTOR).endAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void stagingStillRejectsFiftyNineSeconds() {
        properties.setMinDurationMinutes(1);
        assertRejected(request(59), "AUCTION_DURATION_TOO_SHORT");
        verifyNoInteractions(catalog, inventory, reputation, creation, auctions, outbox);
    }

    @ParameterizedTest(name = "duration {0} giây không hợp lệ")
    @ValueSource(longs = {0, -1})
    void stagingStillRejectsAnEmptyOrReversedWindow(long seconds) {
        properties.setMinDurationMinutes(1);
        assertRejected(request(seconds), "AUCTION_INVALID_TIME_WINDOW");
        verifyNoInteractions(catalog, inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void stagingStillRejectsAStartOutsideTheFiveSecondTolerance() {
        properties.setMinDurationMinutes(1);
        var request = new CreateAuctionRequest(PRODUCT, SKU, null, new BigDecimal("10000"),
                new BigDecimal("1000"), NOW.minusSeconds(6), NOW.plusSeconds(174));
        assertRejected(request, "AUCTION_START_IN_PAST");
        verifyNoInteractions(catalog, inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void stagingStillRejectsAuctionsLongerThanTheConfiguredMaximum() {
        properties.setMinDurationMinutes(1);
        assertRejected(request(168 * 3600L + 1), "AUCTION_DURATION_TOO_LONG");
        verifyNoInteractions(catalog, inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void shortDemoWindowDoesNotBypassSellerOwnership() {
        properties.setMinDurationMinutes(1);
        when(catalog.requireProduct(PRODUCT)).thenReturn(product(UUID.randomUUID(), IMAGE));
        assertRejected(request(180), "AUCTION_FORBIDDEN");
        verifyNoInteractions(inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void shortDemoWindowDoesNotBypassTheProductImageRequirement() {
        properties.setMinDurationMinutes(1);
        when(catalog.requireProduct(PRODUCT)).thenReturn(product(SELLER, ""));
        assertRejected(request(180), "AUCTION_IMAGE_REQUIRED");
        verifyNoInteractions(inventory, reputation, creation, auctions, outbox);
    }

    @Test
    void shortDemoWindowDoesNotBypassAvailableStock() {
        properties.setMinDurationMinutes(1);
        when(catalog.requireProduct(PRODUCT)).thenReturn(product(SELLER, IMAGE));
        when(inventory.available(SKU)).thenReturn(ApiResponse.of(new AuctionInventoryClient.Stock(SKU, 0)));
        assertRejected(request(180), "AUCTION_STOCK_UNAVAILABLE");
        verifyNoInteractions(reputation, creation, auctions, outbox);
    }

    private void validProductAndStock() {
        when(catalog.requireProduct(PRODUCT)).thenReturn(product(SELLER, IMAGE));
        when(inventory.available(SKU)).thenReturn(ApiResponse.of(new AuctionInventoryClient.Stock(SKU, 2)));
        when(creation.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CatalogClient.Product product(UUID owner, String image) {
        return new CatalogClient.Product(owner, "Demo shirt", List.of(new CatalogClient.Sku(SKU)),
                List.of(new CatalogClient.Image(image)));
    }

    private CreateAuctionRequest request(long durationSeconds) {
        return new CreateAuctionRequest(PRODUCT, SKU, null, new BigDecimal("10000"),
                new BigDecimal("1000"), NOW, NOW.plusSeconds(durationSeconds));
    }

    private void assertRejected(CreateAuctionRequest request, String errorCode) {
        assertThatThrownBy(() -> service.create(request, ACTOR))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(errorCode));
    }
}
