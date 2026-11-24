package com.group5.commerceservice.order.summary;

import com.group5.commerceservice.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderSummaryResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String timeZone,
        long orderCount,
        long paidOrderCount,
        BigDecimal revenue,
        List<StatusCount> statuses,
        List<DailyPoint> daily) {

    public record StatusCount(OrderStatus status, long count) {
    }

    public record DailyPoint(LocalDate date, long orderCount, long paidOrderCount, BigDecimal revenue) {
    }
}
