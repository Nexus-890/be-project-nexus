package com.group5.auctionservice.bidding;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.shared.config.AuctionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** #149: kiểm tra policy và transaction bằng mock; không mở kết nối DB thật. */
class AntiSnipingPolicyTest {
    private static final UUID AUCTION_ID = UUID.fromString("14900000-0000-4000-8000-000000000001");
    private static final UUID BIDDER_ID = UUID.fromString("14900000-0000-4000-8000-000000000002");

    private final AuctionRepository auctions = mock(AuctionRepository.class);
    private final AuctionProperties properties = new AuctionProperties();
    private final AntiSnipingPolicy policy = new AntiSnipingPolicy(auctions, properties);

    @Test
    void defaultsPassTheAcceptedAuctionAndBidderToTheFiveMinutePolicy() {
        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);

        verify(auctions).extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30);
        verifyNoMoreInteractions(auctions);
    }

    @Test
    void disabledSwitchNeverAttemptsToChangeAnAuction() {
        properties.setAntiSnipingEnabled(false);

        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);

        verifyNoInteractions(auctions);
    }

    @Test
    void theSamePolicyUsesUpdatedWindowAndCapForTheNextBid() {
        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);
        verify(auctions).extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30);
        clearInvocations(auctions);
        properties.setAntiSnipingExtensionMinutes(2);
        properties.setAntiSnipingMaxExtensionMinutes(7);

        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);

        verify(auctions).extendEndAt(AUCTION_ID, BIDDER_ID, 2, 7);
        verifyNoMoreInteractions(auctions);
    }

    @Test
    void theSamePolicyCanBeDisabledAndEnabledAgain() {
        properties.setAntiSnipingEnabled(false);
        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);
        verifyNoInteractions(auctions);

        properties.setAntiSnipingEnabled(true);
        policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID);

        verify(auctions).extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30);
    }

    @Test
    void noEligibleExtensionDoesNotRefuseAnAlreadyAcceptedBid() {
        // SQL trả 0 khi bid ngoài window hoặc auction đã chạm cap.
        when(auctions.extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30)).thenReturn(0);

        assertThatCode(() -> policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void aRepositoryFailureIsPropagatedToTheBidTransaction() {
        var failure = new DataAccessResourceFailureException("Extension write failed");
        when(auctions.extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30)).thenThrow(failure);

        assertThatThrownBy(() -> policy.extendAcceptedBid(AUCTION_ID, BIDDER_ID))
                .isSameAs(failure);
    }

    @Test
    void springProxyRejectsAnExtensionWithoutTheBidTransaction() {
        DataSource dataSource = mock(DataSource.class);
        AntiSnipingPolicy transactional = transactionalPolicy(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transactional.extendAcceptedBid(AUCTION_ID, BIDDER_ID))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(dataSource, auctions);
    }

    @Test
    void extensionJoinsTheExistingTransactionInsteadOfOpeningAnotherConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        AntiSnipingPolicy transactional = transactionalPolicy(transactions);
        when(auctions.extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isTrue();
            return 1;
        });

        new TransactionTemplate(transactions).executeWithoutResult(status ->
                transactional.extendAcceptedBid(AUCTION_ID, BIDDER_ID));

        verify(dataSource).getConnection();
        verify(connection).commit();
        verify(connection, never()).rollback();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    @Test
    void extensionFailureRollsBackTheEnclosingTransaction() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(dataSource);
        AntiSnipingPolicy transactional = transactionalPolicy(transactions);
        var failure = new DataAccessResourceFailureException("Extension write failed");
        doThrow(failure).when(auctions).extendEndAt(AUCTION_ID, BIDDER_ID, 5, 30);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                transactional.extendAcceptedBid(AUCTION_ID, BIDDER_ID))).isSameAs(failure);

        verify(connection).rollback();
        verify(connection, never()).commit();
        assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
    }

    private AntiSnipingPolicy transactionalPolicy(DataSourceTransactionManager transactions) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(policy);
        factory.addAdvice(interceptor);
        return (AntiSnipingPolicy) factory.getProxy();
    }
}
