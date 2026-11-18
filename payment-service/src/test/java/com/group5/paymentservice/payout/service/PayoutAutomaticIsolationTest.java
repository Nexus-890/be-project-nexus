package com.group5.paymentservice.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.repository.PayoutDispatchRepository;
import com.group5.paymentservice.payout.repository.PayoutHistoryRepository;
import com.group5.paymentservice.payout.repository.PayoutMovementRepository;
import com.group5.paymentservice.payout.repository.PayoutRepository;
import com.group5.paymentservice.sellerbank.security.BankAccountCipher;

import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class PayoutAutomaticIsolationTest {
    @Test
    void disabledJobsCannotSendOrLookupEvenWhenDispatcherIsCalledDirectly() {
        var repository = mock(PayoutDispatchRepository.class);
        var transactions = mock(TransactionTemplate.class);
        var gateway = mock(PayoutGateway.class);
        var dispatcher =
                new PayoutDispatchService(
                        repository,
                        transactions,
                        mock(PayoutService.class),
                        gateway,
                        mock(BankAccountCipher.class),
                        new PayoutProperties(),
                        new ManualPayoutProperties());
        assertThat(dispatcher.dispatchOne()).isFalse();
        dispatcher.reconcile();
        verifyNoInteractions(repository, transactions, gateway);
    }

    @Test
    void manualIntakeAlsoBlocksAutomaticEntryPoints() {
        var repository = mock(PayoutDispatchRepository.class);
        var transactions = mock(TransactionTemplate.class);
        var gateway = mock(PayoutGateway.class);
        var properties = new PayoutProperties();
        properties.setJobsEnabled(true);
        var manual = new ManualPayoutProperties();
        manual.setEnabled(true);
        var dispatcher =
                new PayoutDispatchService(
                        repository,
                        transactions,
                        mock(PayoutService.class),
                        gateway,
                        mock(BankAccountCipher.class),
                        properties,
                        manual);
        assertThat(dispatcher.dispatchOne()).isFalse();
        dispatcher.reconcile();
        verifyNoInteractions(repository, transactions, gateway);
    }

    @Test
    void manualCallbackAndOldRetryCannotCreateFinancialMovements() {
        UUID id = UUID.randomUUID();
        var repository = mock(PayoutRepository.class);
        var histories = mock(PayoutHistoryRepository.class);
        var movements = mock(PayoutMovementRepository.class);
        var gateway = mock(PayoutGateway.class);
        var outbox = mock(OutboxWriter.class);
        when(repository.findLocked(id)).thenReturn(Optional.of(Map.of("execution_mode", "MANUAL")));
        var service =
                new PayoutService(
                        repository,
                        histories,
                        movements,
                        new PayoutProperties(),
                        new ManualPayoutProperties(),
                        mock(BankAccountCipher.class),
                        gateway,
                        outbox,
                        mock(EventEnvelopeFactory.class),
                        Tracer.NOOP);
        service.apply(
                id,
                1,
                "sandbox",
                new PayoutGateway.Result(PayoutGateway.State.SUCCEEDED, "forged", "SUCCESS"),
                true);
        service.apply(
                id,
                1,
                "manual",
                new PayoutGateway.Result(PayoutGateway.State.RETURNED, "late", "RETURNED"),
                false);
        assertThatThrownBy(
                        () ->
                                service.retry(
                                        new CurrentUser(
                                                UUID.randomUUID(), Set.of("ADMIN"), Set.of()),
                                        id,
                                        UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(histories, movements, gateway, outbox);
    }
}
