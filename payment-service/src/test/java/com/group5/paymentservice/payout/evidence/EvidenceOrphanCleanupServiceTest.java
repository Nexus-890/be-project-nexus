package com.group5.paymentservice.payout.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.repository.ManualPayoutRepository;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

class EvidenceOrphanCleanupServiceTest {
    private static final String PAYOUT = "00000000-0000-0000-0000-000000000001";
    private static final String ATTEMPT = "00000000-0000-0000-0000-000000000002";
    private static final String PREFIX = PAYOUT + "/" + ATTEMPT;
    private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
    private final PayoutEvidenceStorage storage = mock(PayoutEvidenceStorage.class);
    private final ManualPayoutRepository repository = mock(ManualPayoutRepository.class);
    private final EvidenceOrphanCleanupService service =
            new EvidenceOrphanCleanupService(
                    storage,
                    repository,
                    new ManualPayoutProperties(),
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyOldUnreferencedObjectsInTheOwnedUuidLayoutAreDeleted() {
        tree(
                List.of(
                        file(3, NOW.minusSeconds(48 * 3600)),
                        file(4, NOW.minusSeconds(48 * 3600)),
                        file(5, NOW.minusSeconds(60)),
                        file(6, null),
                        new PayoutEvidenceStorage.StoredObject(
                                "arbitrary-object.pdf", false, NOW.minusSeconds(48 * 3600))));
        when(repository.evidenceObjectExists(PREFIX + "/" + filename(3))).thenReturn(true);
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage).delete(PREFIX + "/" + filename(4));
        verify(storage, never()).delete(PREFIX + "/" + filename(3));
        verify(storage, never()).delete(PREFIX + "/" + filename(5));
        verify(storage, never()).delete(PREFIX + "/" + filename(6));
        verify(storage, never()).delete(PREFIX + "/arbitrary-object.pdf");
    }

    @Test
    void anUnavailableDependencyNeverDeletesBlindlyAndTheNextTickRetries() {
        tree(List.of(file(3, NOW.minusSeconds(48 * 3600))));
        when(repository.evidenceObjectExists(anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(service::cleanup).isInstanceOf(IllegalStateException.class);
        verify(storage, never()).delete(anyString());
        doReturn(false).when(repository).evidenceObjectExists(anyString());
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage).delete(PREFIX + "/" + filename(3));
    }

    @Test
    void missingConfigurationDoesNotCallTheStorageApi() {
        when(storage.configured()).thenReturn(false);
        assertThat(service.cleanup()).isZero();
        verify(storage, never()).list(anyString(), anyInt(), anyInt());
        verifyNoInteractions(repository);
    }

    @Test
    void aLostDeleteResponseIsRetriedSafelyRatherThanForgotten() {
        tree(List.of(file(3, NOW.minusSeconds(48 * 3600))));
        doThrow(new IllegalStateException("storage unavailable"))
                .doNothing()
                .when(storage)
                .delete(PREFIX + "/" + filename(3));
        assertThatThrownBy(service::cleanup).isInstanceOf(IllegalStateException.class);
        assertThat(service.cleanup()).isEqualTo(1);
        verify(storage, times(2)).delete(PREFIX + "/" + filename(3));
    }

    @Test
    void aShrinkingListingAndPerTickBudgetDoNotSkipRemainingOrphans() {
        var remaining = new ArrayList<PayoutEvidenceStorage.StoredObject>();
        for (int id = 1; id <= 101; id++) remaining.add(file(id, NOW.minusSeconds(48 * 3600)));
        tree(List.of());
        when(storage.list(eq(PREFIX), anyInt(), eq(100)))
                .thenAnswer(
                        invocation -> {
                            int offset = invocation.getArgument(1);
                            return remaining.stream().skip(offset).limit(100).toList();
                        });
        doAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            remaining.removeIf(object -> key.equals(PREFIX + "/" + object.name()));
                            return null;
                        })
                .when(storage)
                .delete(anyString());
        assertThat(service.cleanup()).isEqualTo(100);
        assertThat(remaining).hasSize(1);
        assertThat(service.cleanup()).isEqualTo(1);
        assertThat(remaining).isEmpty();
        verify(storage, times(101)).delete(anyString());
    }

    private void tree(List<PayoutEvidenceStorage.StoredObject> files) {
        when(storage.configured()).thenReturn(true);
        when(storage.list("", 0, 100))
                .thenReturn(List.of(new PayoutEvidenceStorage.StoredObject(PAYOUT, true, null)));
        when(storage.list(PAYOUT, 0, 100))
                .thenReturn(List.of(new PayoutEvidenceStorage.StoredObject(ATTEMPT, true, null)));
        when(storage.list(PREFIX, 0, 100)).thenReturn(files);
    }

    private PayoutEvidenceStorage.StoredObject file(int id, Instant createdAt) {
        return new PayoutEvidenceStorage.StoredObject(filename(id), false, createdAt);
    }

    private String filename(int id) {
        return String.format("00000000-0000-0000-0000-%012d.pdf", id);
    }
}
