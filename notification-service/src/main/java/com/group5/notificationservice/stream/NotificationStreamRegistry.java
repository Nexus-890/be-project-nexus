package com.group5.notificationservice.stream;

import com.group5.common_core.exception.DomainException;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** DB cursors observe committed inbox changes on any replica; reconnect always sends the current snapshot. */
@Component
public class NotificationStreamRegistry {
    private final NotificationInboxQuery inbox;
    private final ConcurrentHashMap<UUID, Connection> clients = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("notification-stream-timer").factory());
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger count = new AtomicInteger();

    public NotificationStreamRegistry(NotificationInboxQuery inbox) {
        this.inbox = inbox;
        timer.scheduleAtFixedRate(this::poll, 2, 2, TimeUnit.SECONDS);
    }

    public SseEmitter connect(UUID userId, Instant tokenExpiry) {
        Instant now = Instant.now();
        if (tokenExpiry != null && !tokenExpiry.isAfter(now)) throw new DomainException(NotificationErrorCode.UNAUTHENTICATED);
        if (count.incrementAndGet() > 1000) {
            count.decrementAndGet(); throw new DomainException(NotificationErrorCode.STREAM_BUSY);
        }
        Instant deadline = tokenExpiry != null && tokenExpiry.isBefore(now.plusSeconds(30)) ? tokenExpiry : now.plusSeconds(30);
        Connection connection = new Connection(userId, deadline);
        clients.put(connection.id, connection);
        connection.emitter.onCompletion(connection::remove);
        connection.emitter.onTimeout(connection::complete);
        connection.emitter.onError(ignored -> connection.remove());
        try { connection.send(inbox.snapshot(userId)); }
        catch (IOException | RuntimeException failure) { connection.complete(); throw new IllegalStateException("Cannot open notification stream", failure); }
        return connection.emitter;
    }

    private void poll() {
        clients.values().forEach(client -> {
            if (client.writing.compareAndSet(false, true)) writers.submit(() -> {
                try {
                    if (!Instant.now().isBefore(client.deadline)) client.complete();
                    else client.send(inbox.snapshot(client.userId));
                } catch (IOException | RuntimeException failure) { client.complete(); }
                finally { client.writing.set(false); }
            });
        });
    }

    @PreDestroy
    public void close() { timer.shutdownNow(); clients.values().forEach(Connection::complete); writers.shutdownNow(); }

    private final class Connection {
        final UUID id = UUID.randomUUID(), userId;
        final Instant deadline;
        final SseEmitter emitter;
        final AtomicBoolean writing = new AtomicBoolean();
        String revision;
        Connection(UUID userId, Instant deadline) {
            this.userId = userId; this.deadline = deadline;
            this.emitter = new SseEmitter(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()));
        }
        synchronized void send(NotificationInboxQuery.Snapshot snapshot) throws IOException {
            if (!snapshot.revision().equals(revision)) {
                emitter.send(SseEmitter.event().name("notifications").id(snapshot.revision()).reconnectTime(1000).data(snapshot));
                revision = snapshot.revision();
            } else emitter.send(SseEmitter.event().name("heartbeat").data(Instant.now().toString()));
        }
        void remove() { if (clients.remove(id, this)) count.decrementAndGet(); }
        void complete() { remove(); emitter.complete(); }
    }
}
