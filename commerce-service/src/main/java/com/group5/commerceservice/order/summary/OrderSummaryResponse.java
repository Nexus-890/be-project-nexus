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
        List<DailyPoint> daily,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Profit profit) {

    public OrderSummaryResponse(OffsetDateTime from, OffsetDateTime to, String timeZone, long orderCount,
                                long paidOrderCount, BigDecimal revenue, List<StatusCount> statuses, List<DailyPoint> daily) {
        this(from, to, timeZone, orderCount, paidOrderCount, revenue, statuses, daily, null);
    }

    public record Profit(BigDecimal costOfGoods, BigDecimal grossProfit, long coveredOrderCount,
                         long missingCostOrderCount) { }

    public record StatusCount(OrderStatus status, long count) {
    }

    public record DailyPoint(LocalDate date, long orderCount, long paidOrderCount, BigDecimal revenue) {
    }
}
