package com.group5.auctionservice.stream;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Giới hạn tài nguyên SSE trên từng instance, độc lập với số auction đang mở. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties("nexus.auction.stream")
public class AuctionStreamProperties {
    @Positive
    private int maxConnections = 2_000;
    @NotNull
    private Duration timeout = Duration.ofMinutes(30);
    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(15);
    @NotNull
    private Duration coalesceInterval = Duration.ofMillis(200);

    /** Không cho cấu hình chu kỳ âm hoặc bằng không gây vòng lặp bận và giữ kết nối vô hạn. */
    @AssertTrue(message = "Auction stream durations must be positive")
    public boolean isDurationRangeValid() {
        return positive(timeout) && positive(heartbeatInterval) && positive(coalesceInterval);
    }

    /** Chu kỳ nhỏ nhất một millisecond, phù hợp đơn vị của scheduler và SseEmitter. */
    private static boolean positive(Duration value) {
        return value != null && value.toMillis() > 0;
    }
}