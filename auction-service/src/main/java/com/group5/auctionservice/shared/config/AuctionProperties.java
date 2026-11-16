package com.group5.auctionservice.shared.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/** JavaBean không final để RefreshScope có thể thay cấu hình qua proxy. */
@Getter
@Setter
@Validated
@ConfigurationProperties("nexus.auction")
public class AuctionProperties {
    @Positive
    private int minDurationMinutes = 60;

    @Positive
    private int maxDurationHours = 168;

    @Positive
    private int maxActivePerSeller = 5;

    @Positive
    private int paymentDeadlineHours = 24;

    @NotNull
    @Positive
    @Digits(integer = 17, fraction = 2)
    private BigDecimal defaultBidIncrement = BigDecimal.TEN;

    // SRS 2.6: bật mặc định, window và mỗi lần gia hạn đều là 5 phút.
    private boolean antiSnipingEnabled = true;

    @Positive
    private int antiSnipingExtensionMinutes = 5;

    // Tổng thời gian cộng thêm sau original_end_at; default kỹ thuật cho #149.
    @Positive
    private int antiSnipingMaxExtensionMinutes = 30;

    @AssertTrue(message = "nexus.auction.min-duration-minutes exceeds the maximum duration")
    public boolean isDurationRangeValid() {
        return Duration.ofMinutes(minDurationMinutes).compareTo(Duration.ofHours(maxDurationHours)) <= 0;
    }
}
