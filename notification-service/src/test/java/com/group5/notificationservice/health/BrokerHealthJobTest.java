package com.group5.notificationservice.health;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerHealthJobTest {

    private KafkaBrokerProbe probe;
    private BrokerHealthRepository repository;
    private BrokerHealthJob job;
    private BrokerHealth kafka;

    @BeforeEach
    void setup() {
        probe = mock(KafkaBrokerProbe.class);
        repository = mock(BrokerHealthRepository.class);
        job = new BrokerHealthJob(probe, repository);
        kafka = new BrokerHealth(BrokerHealthJob.KAFKA);
        when(repository.findById(BrokerHealthJob.KAFKA)).thenReturn(Optional.of(kafka));
    }

    @Test
    void oneFailedAttemptIsRetriedBeforeMarkingDown() {
        when(probe.reachable()).thenReturn(false, true);

        job.checkBrokers();

        verify(probe, times(2)).reachable();
        assertThat(kafka.getStatus()).isEqualTo(HealthStatus.UP);
        assertThat(kafka.getLastFailureAt()).isNull();
        verify(repository).save(kafka);
    }

    @Test
    void aFailedRetryMarksTheBrokerDown() {
        when(probe.reachable()).thenReturn(false, false);

        job.checkBrokers();

        verify(probe, times(2)).reachable();
        assertThat(kafka.getStatus()).isEqualTo(HealthStatus.DOWN);
        assertThat(kafka.getLastFailureAt()).isNotNull();
    }

    @Test
    void theNextSuccessfulRunRecordsTheRecovery() {
        when(probe.reachable()).thenReturn(false, false, true);

        job.checkBrokers();
        job.checkBrokers();

        assertThat(kafka.getStatus()).isEqualTo(HealthStatus.UP);
        assertThat(kafka.getLastRecoveredAt()).isAfterOrEqualTo(kafka.getLastFailureAt());
    }

    @Test
    void aReachableBrokerIsCheckedOnce() {
        when(probe.reachable()).thenReturn(true);

        job.checkBrokers();

        verify(probe, times(1)).reachable();
        assertThat(kafka.getStatus()).isEqualTo(HealthStatus.UP);
    }
}
