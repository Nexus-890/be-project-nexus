package com.group5.notificationservice.stream;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationStreamRegistryTest {
    @Test
    void streamsReadOnlyTheirAuthenticatedInboxAndReauthorizeWithinThirtySeconds() {
        var query = mock(NotificationInboxQuery.class);
        UUID user = UUID.randomUUID();
        when(query.snapshot(user)).thenReturn(new NotificationInboxQuery.Snapshot(1, "17"));
        var streams = new NotificationStreamRegistry(query);
        try {
            assertThat(streams.connect(user, Instant.now().plusSeconds(600)).getTimeout()).isBetween(1L, 30_000L);
            assertThat(streams.connect(user, Instant.now().plusSeconds(5)).getTimeout()).isBetween(1L, 5_000L);
            verify(query, times(2)).snapshot(user);
            assertThatThrownBy(() -> streams.connect(user, Instant.now().minusSeconds(1)))
                    .extracting("errorCode").isEqualTo("NOTIFICATION_UNAUTHENTICATED");
        } finally { streams.close(); }
    }
}
