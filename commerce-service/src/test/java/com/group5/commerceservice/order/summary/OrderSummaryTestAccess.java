package com.group5.commerceservice.order.summary;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

public final class OrderSummaryTestAccess {
    private OrderSummaryTestAccess() {
    }

    public static OrderSummaryService service(JdbcTemplate jdbc, Clock clock) {
        return new OrderSummaryService(jdbc, clock);
    }
}
