package com.group5.paymentservice.payout.dto.request;

import com.group5.paymentservice.payout.enums.PayoutStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PayoutListRequest(UUID sellerId, List<@NotNull PayoutStatus> status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @Min(0) Integer page, @Min(1) @Max(100) Integer size) {
    public PayoutListRequest {
        status = status == null ? List.of() : status.stream().distinct().toList();
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}
