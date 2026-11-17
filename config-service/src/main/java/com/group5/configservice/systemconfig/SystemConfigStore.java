package com.group5.configservice.systemconfig;

import com.group5.common_events.EventEnvelope;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Một file bền vững chứa đồng thời giá trị và audit chờ gửi; một tiến trình sở hữu file. */
@Component
public class SystemConfigStore implements AutoCloseable {
    private final Path file;
    private final JsonMapper mapper;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private State state;

    /** Khoá file riêng để hai Config Server không ghi đè cùng một kho cấu hình. */
    public SystemConfigStore(@Value("${nexus.system-config.state-file:./data/system-configs.json}") String file,
                             JsonMapper mapper) throws IOException {
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.mapper = mapper;
        Files.createDirectories(this.file.getParent());
        this.lockChannel = FileChannel.open(this.file.resolveSibling(this.file.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { this.processLock = lockChannel.tryLock(); }
        catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
        if (processLock == null) {
            lockChannel.close();
            throw new IOException("System configuration state is owned by another process");
        }
        try {
            this.state = Files.exists(this.file)
                    ? mapper.readValue(Files.readString(this.file), State.class)
                    : new State(new LinkedHashMap<>(), new ArrayList<>(), null);
            // Giá trị cũ giữ nguyên kiểu số/boolean sau khi khởi động lại.
            for (var entry : this.state.values().entrySet()) SystemConfigKey.require(entry.getKey()).validate(entry.getValue());
            // Crash giữa refresh và commit: roll-forward intent đã ghi bền vững, giữ nguyên eventId.
            if (this.state.staged() != null) {
                Intent intent = this.state.staged();
                SystemConfigKey.require(intent.key()).validate(intent.value());
                commit(intent.key(), intent.value(), intent.event());
            }
        } catch (IOException | RuntimeException failure) {
            processLock.release();
            lockChannel.close();
            throw failure;
        }
    }

    /** Sao chép giá trị đã chấp nhận để không lộ bộ nhớ mutable của kho. */
    public synchronized Map<String, Object> values() { return new LinkedHashMap<>(state.values()); }

    /** Lấy các sự kiện chưa nhận ACK; eventId không đổi khi retry hoặc restart. */
    public synchronized List<EventEnvelope<?>> pending() { return List.copyOf(state.pending()); }

    /** Ghi intent trước network; restart có thể phục hồi giá trị và audit nếu tiến trình chết giữa chừng. */
    public synchronized void stage(String key, Object value, EventEnvelope<?> event) throws IOException {
        if (state.staged() != null) throw new IOException("A configuration change is already staged");
        persist(new State(values(), new ArrayList<>(state.pending()), new Intent(key, value, event)));
    }

    /** Hủy intent sau lỗi đã biết; không để audit của thay đổi bị từ chối xuất hiện sau restart. */
    public synchronized void abort() throws IOException {
        persist(new State(values(), new ArrayList<>(state.pending()), null));
    }

    /** Ghi giá trị cùng audit vào một lần thay file nguyên tử trước khi trả thành công. */
    public synchronized void commit(String key, Object value, EventEnvelope<?> event) throws IOException {
        Map<String, Object> values = values();
        values.put(key, value);
        List<EventEnvelope<?>> pending = new ArrayList<>(state.pending());
        pending.add(event);
        persist(new State(values, pending, null));
    }

    /** Chỉ xoá sự kiện sau ACK của recorder; crash trước xoá có thể gửi lại cùng eventId. */
    public synchronized void acknowledge(UUID eventId) throws IOException {
        List<EventEnvelope<?>> pending = new ArrayList<>(state.pending());
        pending.removeIf(event -> event.eventId().equals(eventId));
        persist(new State(values(), pending, state.staged()));
    }

    /** Đồng bộ nội dung xuống đĩa rồi rename; không fallback sang ghi file tại chỗ. */
    private void persist(State next) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(next);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
        } finally { Files.deleteIfExists(temporary); }
    }

    /** Giải phóng khoá khi ứng dụng dừng để tiến trình tiếp theo tiếp tục gửi audit. */
    @PreDestroy
    public void close() throws IOException { processLock.release(); lockChannel.close(); }

    /** Snapshot JSON chỉ chứa các key trong SRS và sự kiện không chứa bí mật. */
    public record State(Map<String, Object> values, List<EventEnvelope<?>> pending, Intent staged) { }
    public record Intent(String key, Object value, EventEnvelope<?> event) { }
}
