package com.group5.paymentservice.payout.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.group5.paymentservice.payout.evidence.EvidenceOrphanCleanupService;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class EvidenceOrphanCleanupJobTest {
    @Test
    void externalStorageWorkDoesNotBlockTheSharedSchedulerOrOverlap() throws Exception {
        var service = mock(EvidenceOrphanCleanupService.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        when(service.cleanup())
                .thenAnswer(
                        invocation -> {
                            worker.set(Thread.currentThread());
                            started.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return 0;
                        });
        var job = new EvidenceOrphanCleanupJob(service);
        try {
            job.clean();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotEqualTo(Thread.currentThread());
            job.clean();
            verify(service, times(1)).cleanup();
        } finally {
            release.countDown();
            job.close();
        }
    }
}
