package com.group5.notificationservice.health;

import com.group5.notificationservice.eventlog.EventLogRecorder;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ghi các envelope chuyển trạng thái đang chờ trong Redis vào event_logs qua recorder của #192.
 * CHẠY TRÊN THREAD RIÊNG, KHÔNG DÙNG SCHEDULER CỦA @Scheduled -> POSTGRES CHẬM HAY TREO CHỈ GIỮ THREAD NÀY,
 * {@link BrokerHealthJob} VẪN KIỂM TRA KAFKA ĐÚNG CHU KỲ.
 */
@Slf4j
@Component
public class HealthEventWriter implements InitializingBean, DisposableBean {

    static final String LOCK = "healthEventWriter";
    private static final int BATCH = 20;
    private static final Duration ROUND_BUDGET = Duration.ofSeconds(10);
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

    private final HealthStateStore store;
    private final EventLogRecorder recorder;
    private final LockingTaskExecutor locks;
    private final int batch;
    private final Duration budget;
    private final Duration interval;
    private final Duration maxBackoff;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("health-event-writer").daemon().factory());
    private volatile Duration backoff;

    @Autowired
    public HealthEventWriter(HealthStateStore store, EventLogRecorder recorder, LockProvider lockProvider) {
        this(store, recorder, new DefaultLockingTaskExecutor(lockProvider), BATCH, ROUND_BUDGET, INTERVAL, MAX_BACKOFF);
    }

    HealthEventWriter(HealthStateStore store, EventLogRecorder recorder, LockingTaskExecutor locks, int batch,
                      Duration budget, Duration interval, Duration maxBackoff) {
        this.store = store;
        this.recorder = recorder;
        this.locks = locks;
        this.batch = batch;
        this.budget = budget;
        this.interval = interval;
        this.maxBackoff = maxBackoff;
    }

    @Override
    public void afterPropertiesSet() {
        schedule(interval);
    }

    // KHÓA RIÊNG CHO WRITER: lockAtMostFor 2 PHÚT > NGÂN SÁCH 1 VÒNG (10s) + 1 LẦN CHỜ KẾT NỐI HIKARI (30s)
    void round() {
        Duration next = interval;
        try {
            LockingTaskExecutor.TaskResult<Duration> result = locks.executeWithLock(this::drain,
                    new LockConfiguration(Instant.now(), LOCK, LOCK_AT_MOST_FOR, Duration.ZERO));
            if (result.wasExecuted()) {
                next = result.getResult();
            }
        } catch (Throwable error) {
            log.warn("Health event writer round failed", error);
            next = nextBackoff();
        } finally {
            schedule(next);
        }
    }

    /**
     * Một vòng ghi: tối đa {@code batch} envelope và tối đa {@code budget} thời gian.
     * LỖI ĐẦU TIÊN LÀ DỪNG VÒNG VÀ CHỜ LÂU DẦN (BACKOFF), KHÔNG THỬ TIẾP CÁC ENVELOPE CÒN LẠI.
     * ENVELOPE CHỈ BỊ XÓA KHỎI REDIS SAU KHI GHI XONG, eventId GIỮ NGUYÊN -> GHI LẠI VẪN CHỈ 1 DÒNG (ON CONFLICT).
     *
     * @return thời gian chờ trước vòng sau
     */
    Duration drain() {
        long deadline = System.nanoTime() + budget.toNanos();
        int recorded = 0;
        for (String broker : BrokerHealthJob.BROKERS) {
            for (Map.Entry<String, String> pending : store.pendingEvents(broker).entrySet()) {
                if (recorded == batch || System.nanoTime() - deadline >= 0) {
                    backoff = null;
                    return Duration.ZERO;
                }
                try {
                    recorder.recordRaw(pending.getValue(), null);
                    store.removePending(broker, pending.getKey());
                    recorded++;
                } catch (RuntimeException error) {
                    log.warn("Health transition not recorded yet: broker={} eventId={}",
                            broker, pending.getKey(), error);
                    return nextBackoff();
                }
            }
        }
        backoff = null;
        return interval;
    }

    private Duration nextBackoff() {
        Duration current = backoff;
        backoff = current == null ? interval : min(current.multipliedBy(2), maxBackoff);
        return backoff;
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private void schedule(Duration delay) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(this::round, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
