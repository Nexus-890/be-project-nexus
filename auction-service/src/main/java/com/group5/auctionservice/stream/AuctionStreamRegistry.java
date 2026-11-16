package com.group5.auctionservice.stream;

import com.group5.auctionservice.auction.AuctionStateQuery;
import com.group5.auctionservice.auction.AuctionStateQuery.VersionedState;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/** Quản lý kết nối tại instance hiện tại; Kafka fan-out thay cho Redis hay sticky session. */
@Component
public class AuctionStreamRegistry {
    private static final EnumSet<AuctionStatus> FINAL_STATES = EnumSet.of(AuctionStatus.ENDED, AuctionStatus.WON,
            AuctionStatus.SETTLED, AuctionStatus.FAILED, AuctionStatus.CANCELLED, AuctionStatus.PAYMENT_TIMEOUT);
    private final AuctionStateQuery states;
    private final AuctionStreamProperties properties;
    private final Clock clock;
    private final LongFunction<SseEmitter> emitterFactory;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService writers;
    private final ConcurrentHashMap<UUID, Group> groups = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** Dùng virtual thread cho socket ghi chậm để một viewer không chặn consumer hoặc các viewer khác. */
    @Autowired
    public AuctionStreamRegistry(AuctionStateQuery states, AuctionStreamProperties properties, Clock clock) {
        this(states, properties, clock, SseEmitter::new);
    }

    /** Cho unit test thay emitter, giữ nguyên lịch heartbeat và cơ chế gửi của production. */
    AuctionStreamRegistry(AuctionStateQuery states, AuctionStreamProperties properties, Clock clock,
                          LongFunction<SseEmitter> emitterFactory) {
        this.states = states;
        this.properties = properties;
        this.clock = clock;
        this.emitterFactory = emitterFactory;
        this.scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("auction-stream-timer-", 0).factory());
        this.writers = Executors.newVirtualThreadPerTaskExecutor();
        scheduler.scheduleAtFixedRate(this::heartbeat, properties.getHeartbeatInterval().toMillis(),
                properties.getHeartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Gửi snapshot ngay khi kết nối; Last-Event-ID cũ cũng luôn được đồng bộ bằng trạng thái mới nhất. */
    public SseEmitter connect(UUID auctionId) {
        states.refreshVersioned(auctionId); // 404 được phát trước khi stream mở, kể cả auction restricted/deleted.
        if (stopped.get()) throw new DomainException(AuctionErrorCode.AUCTION_STREAM_BUSY);
        if (connections.incrementAndGet() > properties.getMaxConnections()) {
            connections.decrementAndGet();
            throw new DomainException(AuctionErrorCode.AUCTION_STREAM_BUSY);
        }
        Connection connection = null;
        try {
            connection = new Connection(auctionId, emitterFactory.apply(properties.getTimeout().toMillis()));
            Connection registered = connection;
            connection.emitter.onCompletion(registered::remove);
            connection.emitter.onTimeout(registered::complete);
            connection.emitter.onError(ignored -> registered.remove());
            groups.compute(auctionId, (ignored, existing) -> {
                Group group = existing == null ? new Group() : existing;
                group.clients.put(registered.id, registered);
                return group;
            });
            if (stopped.get()) throw new DomainException(AuctionErrorCode.AUCTION_STREAM_BUSY);
            // Đọc lại sau đăng ký để không bỏ lỡ thay đổi nằm giữa lần đọc đầu và lúc thêm listener.
            connection.sendState(states.refreshVersioned(auctionId));
            return connection.emitter;
        } catch (RuntimeException | IOException failure) {
            if (connection == null) connections.decrementAndGet();
            else connection.complete();
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Cannot open auction stream", failure);
        }
    }

    /** Gom một burst 200 ms thành một lần đọc DB và một snapshot mới nhất cho mỗi viewer. */
    public void changed(UUID auctionId) {
        Group group = groups.get(auctionId);
        if (group == null || !group.pending.compareAndSet(false, true) || stopped.get()) return;
        try {
            scheduler.schedule(() -> submit(() -> publish(auctionId, group)),
                    properties.getCoalesceInterval().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            group.pending.set(false);
        }
    }

    /** Bỏ snapshot cache trước khi đọc để bid và anti-sniping cùng xuất hiện trong một thông điệp. */
    private void publish(UUID auctionId, Group group) {
        group.pending.set(false);
        if (group.clients.isEmpty() || stopped.get()) return;
        try {
            VersionedState latest = states.refreshVersioned(auctionId);
            group.clients.values().forEach(connection -> connection.enqueue(latest));
        } catch (RuntimeException failure) {
            // Mất public visibility hoặc DB lỗi: đóng stream để EventSource/FE chuyển sang polling.
            group.clients.values().forEach(Connection::complete);
        }
    }

    /** Heartbeat có tên event để FE nhận hoạt động ngay cả khi giá không đổi trong nhiều phút. */
    private void heartbeat() {
        groups.values().forEach(group -> group.clients.values().forEach(Connection::enqueueHeartbeat));
    }

    /** Chỉ gửi khi executor còn sống; shutdown không được làm văng lỗi trên listener Kafka. */
    private void submit(Runnable action) {
        if (stopped.get()) return;
        try { writers.execute(action); }
        catch (RejectedExecutionException ignored) { /* Bean đang đóng, callback sẽ dọn listener. */ }
    }

    /** Số kết nối đang giữ slot, dùng cho kiểm chứng cleanup và giới hạn tải. */
    int connectionCount() {
        return connections.get();
    }

    /** Dọn emitter và scheduler khi instance dừng, không giữ kết nối hoặc thread mồ côi. */
    @PreDestroy
    public void close() {
        if (!stopped.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        groups.values().forEach(group -> group.clients.values().forEach(Connection::complete));
        writers.shutdownNow();
    }

    private static final class Group {
        private final ConcurrentHashMap<UUID, Connection> clients = new ConcurrentHashMap<>();
        private final AtomicBoolean pending = new AtomicBoolean();
    }

    /** Mỗi kết nối giữ tối đa một snapshot đang chờ; client chậm không tích lũy task theo số bid. */
    private final class Connection {
        private final UUID id = UUID.randomUUID();
        private final UUID auctionId;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean writing = new AtomicBoolean();
        private final AtomicBoolean heartbeatPending = new AtomicBoolean();
        private final AtomicReference<VersionedState> pending = new AtomicReference<>();
        private long sentVersion = -1;

        /** Gắn emitter với auction và slot đã cấp từ registry. */
        private Connection(UUID auctionId, SseEmitter emitter) {
            this.auctionId = auctionId;
            this.emitter = emitter;
        }

        /** Giữ snapshot version lớn nhất nếu có nhiều event tới trong lúc socket đang ghi. */
        private void enqueue(VersionedState state) {
            pending.accumulateAndGet(state, (previous, next) ->
                    previous == null || next.version() > previous.version() ? next : previous);
            dispatch();
        }

        /** Heartbeat cũng dùng một cờ duy nhất để giới hạn bộ nhớ khi viewer không đọc dữ liệu. */
        private void enqueueHeartbeat() {
            heartbeatPending.set(true);
            dispatch();
        }

        /** Chỉ một writer cho từng socket; các socket khác nhau vẫn ghi độc lập. */
        private void dispatch() {
            if (!closed.get() && writing.compareAndSet(false, true)) submit(this::drain);
        }

        /** Ưu tiên state mới nhất rồi heartbeat; không gửi lại version đã gửi khi Kafka replay. */
        private void drain() {
            try {
                while (!closed.get()) {
                    VersionedState state = pending.getAndSet(null);
                    boolean beat = heartbeatPending.getAndSet(false);
                    if (state == null && !beat) break;
                    if (state != null) sendState(state);
                    else if (beat) sendHeartbeat();
                }
            } catch (IOException | RuntimeException failure) {
                complete();
            } finally {
                writing.set(false);
                if (pending.get() != null || heartbeatPending.get()) dispatch();
            }
        }

        /** Gửi nguyên data của GET /state, dùng auction.version làm SSE id thay vì thời gian event. */
        private synchronized void sendState(VersionedState state) throws IOException {
            if (closed.get() || state.version() <= sentVersion) return;
            emitter.send(SseEmitter.event().name("state").id(Long.toString(state.version()))
                    .data(state.state(), MediaType.APPLICATION_JSON));
            sentVersion = state.version();
            if (FINAL_STATES.contains(state.state().status())) complete();
        }

        /** Không gắn id cho heartbeat để Last-Event-ID luôn chỉ phiên bản auction. */
        private synchronized void sendHeartbeat() throws IOException {
            if (!closed.get()) emitter.send(SseEmitter.event().name("heartbeat").data(clock.instant().toString()));
        }

        /** Callback timeout và trạng thái kết thúc đều giải phóng slot đúng một lần. */
        private void complete() {
            if (remove()) emitter.complete();
        }

        /** Xóa emitter cụ thể, không xóa nhầm group vừa được client khác tạo lại. */
        private boolean remove() {
            if (!closed.compareAndSet(false, true)) return false;
            pending.set(null);
            heartbeatPending.set(false);
            groups.computeIfPresent(auctionId, (ignored, group) -> {
                group.clients.remove(id);
                return group.clients.isEmpty() ? null : group;
            });
            connections.decrementAndGet();
            return true;
        }
    }
}