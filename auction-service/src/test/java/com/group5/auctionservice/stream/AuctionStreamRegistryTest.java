package com.group5.auctionservice.stream;

import com.group5.auctionservice.auction.AuctionStateQuery;
import com.group5.auctionservice.auction.AuctionStateQuery.VersionedState;
import com.group5.auctionservice.auction.AuctionStatus;
import com.group5.auctionservice.auction.dto.AuctionStateResponse;
import com.group5.auctionservice.shared.exception.AuctionErrorCode;
import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Kiểm tra vòng đời, giới hạn kết nối và fan-out bằng chính registry dùng ở production. */
class AuctionStreamRegistryTest {
    private final UUID auction = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
    private final AuctionStateQuery states = mock(AuctionStateQuery.class);
    private final AuctionStreamProperties properties = new AuctionStreamProperties();
    private final AtomicReference<VersionedState> latest = new AtomicReference<>();
    private final List<RecordingEmitter> emitters = new CopyOnWriteArrayList<>();
    private AuctionStreamRegistry registry;

    /** Cô lập heartbeat trong từng test; snapshot có version độc lập với thời gian server. */
    @BeforeEach
    void setup() {
        properties.setHeartbeatInterval(Duration.ofHours(1));
        latest.set(snapshot(0, AuctionStatus.ACTIVE));
        when(states.refreshVersioned(auction)).thenAnswer(ignored -> latest.get());
    }

    /** Luôn đóng timer và emitter để test khác không nhận callback từ registry cũ. */
    @AfterEach
    void cleanup() {
        if (registry != null) registry.close();
    }

    /** Connect gửi ngay body GET/state cùng version, không bọc ApiResponse và timeout mặc định 30 phút. */
    @Test
    void connectionImmediatelyReceivesCurrentStateAndVersion() {
        start();
        registry.connect(auction);
        assertThat(emitters.getFirst().states()).containsExactly(latest.get().state());
        assertThat(emitters.getFirst().wire.getFirst()).contains("event:state", "id:0");
        assertThat(emitters.getFirst().getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    /** Complete, timeout, error gọi lặp đều chỉ giải phóng một slot và hủy listener tương ứng. */
    @ParameterizedTest
    @ValueSource(strings = {"complete", "timeout", "error"})
    void lifecycleCallbacksRemoveExactlyOnce(String action) {
        start();
        registry.connect(auction);
        var emitter = emitters.getFirst();
        switch (action) {
            case "complete" -> emitter.complete();
            case "timeout" -> emitter.timeout.run();
            default -> emitter.error.accept(new IOException("disconnected"));
        }
        emitter.completion.run();
        emitter.error.accept(new IOException("duplicate callback"));
        assertThat(registry.connectionCount()).isZero();
        clearInvocations(states);
        registry.changed(auction);
        verifyNoInteractions(states);
    }

    /** Hết slot trả lỗi tên AUCTION_STREAM_BUSY; sau cleanup có thể kết nối mới ngay. */
    @Test
    void capacityIsEnforcedAndReleased() {
        properties.setMaxConnections(1);
        start();
        registry.connect(auction);
        assertThatThrownBy(() -> registry.connect(auction)).isInstanceOfSatisfying(DomainException.class,
                failure -> { assertThat(failure.getCode()).isEqualTo(503);
                    assertThat(failure.getErrorCode()).isEqualTo("AUCTION_STREAM_BUSY"); });
        emitters.getFirst().complete();
        registry.connect(auction);
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    /** Auction không tồn tại hoặc không public phải bị từ chối trước khi cấp slot hay tạo emitter. */
    @Test
    void unknownAuctionDoesNotOpenOrReserveAConnection() {
        start();
        when(states.refreshVersioned(auction)).thenThrow(new DomainException(AuctionErrorCode.AUCTION_NOT_FOUND));
        assertThatThrownBy(() -> registry.connect(auction)).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(404));
        assertThat(emitters).isEmpty();
        assertThat(registry.connectionCount()).isZero();
    }

    /** Burst nhiều event trong 200 ms chỉ đọc một snapshot và gửi một state mới tới mỗi viewer. */
    @Test
    void burstIsCoalescedAndDuplicateVersionIsNotSentAgain() {
        start();
        registry.connect(auction);
        registry.connect(auction);
        clearInvocations(states);
        latest.set(snapshot(1, AuctionStatus.ACTIVE));
        for (int event = 0; event < 50; event++) registry.changed(auction);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(emitters.get(0).states()).hasSize(2);
            assertThat(emitters.get(1).states()).hasSize(2);
        });
        verify(states, times(1)).refreshVersioned(auction);
        registry.changed(auction);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(states, times(2)).refreshVersioned(auction));
        assertThat(emitters.getFirst().states()).hasSize(2);
    }

    /** Deadline anti-sniping nằm ngay trong body state mới, không cần client tải lại trang. */
    @Test
    void newStateIncludesTheExtendedDeadline() {
        start();
        registry.connect(auction);
        var before = latest.get().state();
        var after = new AuctionStateResponse(auction, new BigDecimal("120"), 2, BigDecimal.TEN,
                "N***A", before.endAt().plusSeconds(300), AuctionStatus.ACTIVE, clock.instant());
        latest.set(new VersionedState(2, after));
        registry.changed(auction);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(emitters.getFirst().states()).containsExactly(before, after));
    }

    /** Gửi trạng thái kết thúc lần cuối rồi đóng stream và trả slot cho kết nối khác. */
    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = {"ENDED", "WON", "SETTLED", "FAILED", "CANCELLED", "PAYMENT_TIMEOUT"})
    void finalStateIsSentBeforeConnectionCloses(AuctionStatus status) {
        start();
        registry.connect(auction);
        latest.set(snapshot(1, status));
        registry.changed(auction);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(emitters.getFirst().states()).hasSize(2);
            assertThat(registry.connectionCount()).isZero();
            assertThat(emitters.getFirst().completed).isTrue();
        });
        assertThat(emitters.getFirst().states().getLast().status()).isEqualTo(status);
    }

    /** Heartbeat có event riêng và không đổi Last-Event-ID dùng để reconnect. */
    @Test
    void heartbeatDoesNotOverwriteTheAuctionVersion() {
        properties.setHeartbeatInterval(Duration.ofMillis(30));
        start();
        registry.connect(auction);
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(emitters.getFirst().wire).anySatisfy(message ->
                        assertThat(message).contains("event:heartbeat").doesNotContain("id:")));
    }

    /** Socket ghi lỗi phải bị dọn; lỗi một client không được làm ngắt các client còn lại. */
    @Test
    void brokenWriterIsRemovedWithoutInterruptingOtherViewer() {
        start();
        registry.connect(auction);
        registry.connect(auction);
        emitters.getFirst().fail = true;
        latest.set(snapshot(1, AuctionStatus.ACTIVE));
        registry.changed(auction);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(registry.connectionCount()).isEqualTo(1);
            assertThat(emitters.get(1).states()).hasSize(2);
        });
    }

    /** Một socket chậm không chặn viewer khác và chỉ giữ snapshot mới nhất trong hàng đợi riêng. */
    @Test
    void slowViewerDoesNotBlockAnotherViewer() throws Exception {
        start();
        registry.connect(auction);
        registry.connect(auction);
        var slow = emitters.getFirst();
        slow.block = new CountDownLatch(1);
        try {
            latest.set(snapshot(1, AuctionStatus.ACTIVE));
            registry.changed(auction);
            assertThat(slow.started.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(emitters.get(1).states()).hasSize(2));
            latest.set(snapshot(2, AuctionStatus.ACTIVE));
            registry.changed(auction);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(emitters.get(1).states()).hasSize(3));
            slow.block.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(slow.states().getLast().bidCount()).isEqualTo(2));
        } finally { slow.block.countDown(); }
    }

    /** Khởi tạo emitter giả chỉ để quan sát wire data, không thay logic registry. */
    private void start() {
        registry = new AuctionStreamRegistry(states, properties, clock, timeout -> {
            var emitter = new RecordingEmitter(timeout);
            emitters.add(emitter);
            return emitter;
        });
    }

    /** Snapshot công khai không chứa bidderId để stream giữ đúng quyền riêng tư của GET/state. */
    private VersionedState snapshot(long version, AuctionStatus status) {
        return new VersionedState(version, new AuctionStateResponse(auction, new BigDecimal("100"), (int) version,
                BigDecimal.TEN, null, clock.instant().plusSeconds(600), status, clock.instant()));
    }

    /** Adapter ghi nhận event builder và cho phép kích hoạt callback của servlet một cách chủ động. */
    private static final class RecordingEmitter extends SseEmitter {
        private final List<List<Object>> messages = new CopyOnWriteArrayList<>();
        private final List<String> wire = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private Runnable completion = () -> { };
        private Runnable timeout = () -> { };
        private Consumer<Throwable> error = ignored -> { };
        private volatile boolean completed;
        private volatile boolean fail;
        private volatile CountDownLatch block;

        /** Giữ timeout thật để kiểm tra hợp đồng 30 phút. */
        private RecordingEmitter(long timeout) { super(timeout); }
        /** Ghi nguyên từng thành phần SSE thay vì serialize lại theo một implementation giả. */
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (fail) throw new IOException("broken socket");
            if (block != null) {
                started.countDown();
                try { if (!block.await(3, TimeUnit.SECONDS)) throw new IOException("test write timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            }
            List<Object> parts = builder.build().stream().map(DataWithMediaType::getData).toList();
            messages.add(parts);
            wire.add(parts.stream().map(String::valueOf).reduce("", String::concat));
        }
        /** Tách body state khỏi các dòng event/id để đối chiếu đúng DTO công khai. */
        private List<AuctionStateResponse> states() {
            return messages.stream().flatMap(List::stream).filter(AuctionStateResponse.class::isInstance)
                    .map(AuctionStateResponse.class::cast).toList();
        }
        /** Ghi callback completion để mô phỏng servlet kết thúc response. */
        @Override public void onCompletion(Runnable callback) { completion = callback; }
        /** Ghi callback timeout của servlet cho test cleanup. */
        @Override public void onTimeout(Runnable callback) { timeout = callback; }
        /** Ghi callback lỗi socket cho test cleanup. */
        @Override public void onError(Consumer<Throwable> callback) { error = callback; }
        /** Hoàn thành emitter và gọi callback như servlet container. */
        @Override public void complete() { completed = true; completion.run(); }
    }
}