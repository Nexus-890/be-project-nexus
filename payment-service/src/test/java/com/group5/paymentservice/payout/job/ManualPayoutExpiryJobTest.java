package com.group5.paymentservice.payout.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.group5.paymentservice.payout.repository.ManualPayoutRepository;
import com.group5.paymentservice.payout.service.ManualPayoutService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Expiry housekeeping remains asynchronous, fair and isolated from automatic transfer jobs. */
class ManualPayoutExpiryJobTest {
    @Test
    void aFailedPayoutDoesNotStrandTheNextPayoutInTheBatch() throws Exception {
        var repository = mock(ManualPayoutRepository.class);
        var payouts = mock(ManualPayoutService.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var processedSecond = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        when(repository.expiring(null, 100)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("fixture failure")).when(payouts).expire(first);
        doAnswer(
                        call -> {
                            worker.set(Thread.currentThread());
                            processedSecond.countDown();
                            return null;
                        })
                .when(payouts)
                .expire(second);
        var job = new ManualPayoutExpiryJob(repository, payouts);
        try {
            job.expire();
            assertThat(processedSecond.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotEqualTo(Thread.currentThread());
            var order = inOrder(payouts);
            order.verify(payouts).expire(first);
            order.verify(payouts).expire(second);
        } finally {
            job.close();
        }
    }

    @Test
    void theNextTickAdvancesPastAFailedPayoutAndWrapsAfterTheUpperBound() throws Exception {
        var repository = mock(ManualPayoutRepository.class);
        var payouts = mock(ManualPayoutService.class);
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        var wrapped = new CountDownLatch(1);
        var releaseWrap = new CountDownLatch(1);
        var firstPageCalls = new AtomicInteger();
        when(repository.expiring(nullable(UUID.class), eq(100)))
                .thenAnswer(
                        call -> {
                            UUID cursor = call.getArgument(0);
                            if (cursor == null) {
                                if (firstPageCalls.incrementAndGet() == 1) return List.of(first);
                                // Hold the wrapped scan so extra test ticks cannot race another
                                // cycle into assertions.
                                wrapped.countDown();
                                awaitWorker(releaseWrap);
                                return List.of();
                            }
                            return first.equals(cursor) ? List.of(second) : List.of();
                        });
        doThrow(new IllegalStateException("first remains unavailable")).when(payouts).expire(first);
        var job = new ManualPayoutExpiryJob(repository, payouts);
        var ticks = Executors.newSingleThreadScheduledExecutor();
        try {
            ticks.scheduleAtFixedRate(job::expire, 0, 10, TimeUnit.MILLISECONDS);
            assertThat(wrapped.await(2, TimeUnit.SECONDS)).isTrue();
            var order = inOrder(repository, payouts);
            order.verify(repository).expiring(null, 100);
            order.verify(payouts).expire(first);
            order.verify(repository).expiring(first, 100);
            order.verify(payouts).expire(second);
            order.verify(repository).expiring(second, 100);
            order.verify(repository).expiring(null, 100);
            verify(payouts, times(1)).expire(first);
            verify(payouts, times(1)).expire(second);
        } finally {
            ticks.shutdownNow();
            releaseWrap.countDown();
            job.close();
        }
    }

    @Test
    void simultaneousSchedulerTicksReturnWithoutOverlappingTheWorker() throws Exception {
        var repository = mock(ManualPayoutRepository.class);
        var payouts = mock(ManualPayoutService.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var startTicks = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        when(repository.expiring(null, 100))
                .thenAnswer(
                        call -> {
                            worker.set(Thread.currentThread());
                            started.countDown();
                            awaitWorker(release);
                            return List.of();
                        });
        var job = new ManualPayoutExpiryJob(repository, payouts);
        try (var callers = Executors.newFixedThreadPool(4)) {
            var ticks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++)
                ticks.add(
                        callers.submit(
                                () -> {
                                    startTicks.await(2, TimeUnit.SECONDS);
                                    job.expire();
                                    return null;
                                }));
            startTicks.countDown();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (var tick : ticks) tick.get(2, TimeUnit.SECONDS);
            verify(repository, times(1)).expiring(null, 100);
            verifyNoInteractions(payouts);
            assertThat(worker.get()).isNotEqualTo(Thread.currentThread());
        } finally {
            release.countDown();
            job.close();
        }
    }

    private static void awaitWorker(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        }
    }
}
