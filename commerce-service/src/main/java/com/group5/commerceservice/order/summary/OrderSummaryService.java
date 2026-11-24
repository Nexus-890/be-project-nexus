package com.group5.commerceservice.order.summary;

import com.group5.commerceservice.order.OrderStatus;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class OrderSummaryService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_DAYS = 90;
    private static final Set<OrderStatus> PAID = EnumSet.of(
            OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    private static final String SQL = """
            SELECT CAST(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh' AS date) AS day,
                   CAST(status AS text) AS status,
                   count(*) AS orders,
                   coalesce(sum(total_amount), 0) AS amount
            FROM orders
            WHERE created_at >= ? AND created_at < ? AND (CAST(? AS uuid) IS NULL OR seller_id = CAST(? AS uuid))
            GROUP BY 1, 2
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public OrderSummaryService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    OrderSummaryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrderSummaryResponse managed(CurrentUser user, UUID sellerId, int days) {
        requireAccess(user, "ADMIN", "SUPPORT_STAFF");
        return summarize(sellerId, days);
    }

    @Transactional(readOnly = true)
    public OrderSummaryResponse sales(CurrentUser user, int days) {
        requireAccess(user, "SELLER");
        return summarize(user.id(), days);
    }

    private OrderSummaryResponse summarize(UUID sellerId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new DomainException(CommerceErrorCode.INVALID_ORDER_FILTER,
                    "days must be between 1 and " + MAX_DAYS);
        }
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        LocalDate first = today.minusDays(days - 1L);
        OffsetDateTime from = first.atStartOfDay(ZONE).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime();

        TreeMap<LocalDate, Day> buckets = new TreeMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            buckets.put(day, new Day());
        }
        EnumMap<OrderStatus, Long> statusCounts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            statusCounts.put(status, 0L);
        }

        String seller = sellerId == null ? null : sellerId.toString();
        jdbc.query(SQL, row -> {
            OrderStatus status = OrderStatus.valueOf(row.getString("status"));
            long orders = row.getLong("orders");
            statusCounts.merge(status, orders, Long::sum);
            Day day = buckets.get(row.getObject("day", LocalDate.class));
            if (day == null) {
                return;
            }
            day.orders += orders;
            if (PAID.contains(status)) {
                day.paid += orders;
                day.revenue = day.revenue.add(row.getBigDecimal("amount"));
            }
        }, Timestamp.from(from.toInstant()), Timestamp.from(to.toInstant()), seller, seller);

        List<OrderSummaryResponse.DailyPoint> daily = new ArrayList<>();
        long orderCount = 0;
        long paidCount = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        for (var entry : buckets.entrySet()) {
            Day day = entry.getValue();
            BigDecimal dayRevenue = money(day.revenue);
            daily.add(new OrderSummaryResponse.DailyPoint(entry.getKey(), day.orders, day.paid, dayRevenue));
            orderCount += day.orders;
            paidCount += day.paid;
            revenue = revenue.add(dayRevenue);
        }
        List<OrderSummaryResponse.StatusCount> statuses = statusCounts.entrySet().stream()
                .map(entry -> new OrderSummaryResponse.StatusCount(entry.getKey(), entry.getValue()))
                .toList();
        return new OrderSummaryResponse(from, to, ZONE.getId(), orderCount, paidCount, money(revenue),
                statuses, daily);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireAccess(CurrentUser user, String... roles) {
        if (user == null || user.id() == null) {
            throw new DomainException(CommerceErrorCode.UNAUTHENTICATED);
        }
        if (!user.hasPrivilege("ORDER.LIST") || Arrays.stream(roles).noneMatch(user::hasRole)) {
            throw new DomainException(CommerceErrorCode.FORBIDDEN);
        }
    }

    private static final class Day {
        private long orders;
        private long paid;
        private BigDecimal revenue = BigDecimal.ZERO;
    }
}
