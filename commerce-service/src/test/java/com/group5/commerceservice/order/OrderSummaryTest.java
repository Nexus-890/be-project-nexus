package com.group5.commerceservice.order;

import com.group5.commerceservice.PostgresTestDatabase;
import com.group5.commerceservice.order.summary.OrderSummaryResponse;
import com.group5.commerceservice.order.summary.OrderSummaryService;
import com.group5.commerceservice.order.summary.OrderSummaryTestAccess;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(OrderSummaryTest.Config.class)
class OrderSummaryTest {
    private static final String POSTGRES_URL = System.getProperty("nexus.test.postgres.url");
    private static final PostgreSQLContainer POSTGRES = POSTGRES_URL == null
            ? new PostgresTestDatabase("postgres:18-alpine") : null;
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final CurrentUser ADMIN = user("ADMIN");
    private static final CurrentUser SUPPORT = user("SUPPORT_STAFF");

    static {
        if (POSTGRES != null) POSTGRES.start();
    }

    @Autowired private OrderSummaryService summaries;
    @Autowired private DataSource dataSource;
    private CurrentUser sellerA;
    private CurrentUser sellerB;
    private int number;

    @BeforeEach
    void insertOrders() {
        jdbc().execute("TRUNCATE order_groups CASCADE");
        sellerA = user("SELLER");
        sellerB = user("SELLER");
        order(sellerA, OrderStatus.PAID, "100000.00", "2026-11-24T09:00:00Z");
        order(sellerA, OrderStatus.COMPLETED, "250000.00", "2026-11-23T17:30:00Z");
        order(sellerA, OrderStatus.PENDING, "999000.00", "2026-11-24T08:00:00Z");
        order(sellerA, OrderStatus.CANCELED, "500000.00", "2026-11-22T03:00:00Z");
        order(sellerA, OrderStatus.SHIPPED, "70000.00", "2026-11-23T16:59:59Z");
        order(sellerB, OrderStatus.DELIVERED, "40000.00", "2026-11-20T02:00:00Z");
        order(sellerB, OrderStatus.REFUNDED, "80000.00", "2026-11-24T01:00:00Z");
        order(sellerA, OrderStatus.COMPLETED, "123000.00", "2026-10-20T02:00:00Z");
    }

    @Test
    void sellerSummaryCountsOnlyOwnOrdersAndSumsPaidOnes() {
        OrderSummaryResponse summary = summaries.sales(sellerA, 7);

        assertThat(summary.from()).isEqualTo(OffsetDateTime.parse("2026-11-18T00:00:00+07:00"));
        assertThat(summary.to()).isEqualTo(OffsetDateTime.parse("2026-11-25T00:00:00+07:00"));
        assertThat(summary.timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(summary.orderCount()).isEqualTo(5);
        assertThat(summary.paidOrderCount()).isEqualTo(3);
        assertThat(summary.revenue()).isEqualByComparingTo("420000.00");
        assertThat(statuses(summary)).containsEntry(OrderStatus.PAID, 1L).containsEntry(OrderStatus.COMPLETED, 1L)
                .containsEntry(OrderStatus.PENDING, 1L).containsEntry(OrderStatus.CANCELED, 1L)
                .containsEntry(OrderStatus.SHIPPED, 1L).containsEntry(OrderStatus.REFUNDED, 0L)
                .hasSize(OrderStatus.values().length);
    }

    @Test
    void daysAreCutInVietnamTimeAndEmptyDaysStayInTheSeries() {
        OrderSummaryResponse summary = summaries.sales(sellerA, 7);

        assertThat(summary.daily()).hasSize(7);
        assertThat(summary.daily().getFirst().date()).isEqualTo(LocalDate.parse("2026-11-18"));
        assertThat(summary.daily().getLast().date()).isEqualTo(LocalDate.parse("2026-11-24"));
        Map<LocalDate, OrderSummaryResponse.DailyPoint> byDate = summary.daily().stream()
                .collect(Collectors.toMap(OrderSummaryResponse.DailyPoint::date, point -> point));
        assertThat(byDate.get(LocalDate.parse("2026-11-24")).orderCount()).isEqualTo(3);
        assertThat(byDate.get(LocalDate.parse("2026-11-24")).paidOrderCount()).isEqualTo(2);
        assertThat(byDate.get(LocalDate.parse("2026-11-24")).revenue()).isEqualByComparingTo("350000.00");
        assertThat(byDate.get(LocalDate.parse("2026-11-23")).orderCount()).isEqualTo(1);
        assertThat(byDate.get(LocalDate.parse("2026-11-23")).revenue()).isEqualByComparingTo("70000.00");
        assertThat(byDate.get(LocalDate.parse("2026-11-22")).paidOrderCount()).isZero();
        assertThat(byDate.get(LocalDate.parse("2026-11-21")).orderCount()).isZero();
        assertThat(byDate.get(LocalDate.parse("2026-11-21")).revenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void managersSeeEverySellerOrFilterByOne() {
        for (CurrentUser manager : java.util.List.of(ADMIN, SUPPORT)) {
            OrderSummaryResponse all = summaries.managed(manager, null, 7);
            assertThat(all.orderCount()).isEqualTo(7);
            assertThat(all.paidOrderCount()).isEqualTo(4);
            assertThat(all.revenue()).isEqualByComparingTo("460000.00");

            OrderSummaryResponse onlyB = summaries.managed(manager, sellerB.id(), 7);
            assertThat(onlyB.orderCount()).isEqualTo(2);
            assertThat(onlyB.revenue()).isEqualByComparingTo("40000.00");
        }
        assertThat(summaries.managed(ADMIN, null, 90).orderCount()).isEqualTo(8);
        assertThat(summaries.managed(ADMIN, null, 1).orderCount()).isEqualTo(4);
    }

    @Test
    void daysOutsideOneToNinetyAreRejected() {
        for (int days : new int[]{0, -1, 91}) {
            assertThatThrownBy(() -> summaries.sales(sellerA, days)).isInstanceOf(DomainException.class)
                    .extracting("code", "errorCode").containsExactly(400, "COMMERCE_INVALID_ORDER_FILTER");
            assertThatThrownBy(() -> summaries.managed(ADMIN, null, days)).isInstanceOf(DomainException.class)
                    .extracting("code", "errorCode").containsExactly(400, "COMMERCE_INVALID_ORDER_FILTER");
        }
    }

    @Test
    void wrongRolesMissingPrivilegeAndMissingUserAreRejected() {
        for (CurrentUser caller : java.util.List.of(user("BUYER"), sellerA)) {
            assertForbidden(() -> summaries.managed(caller, null, 7));
        }
        for (CurrentUser caller : java.util.List.of(user("BUYER"), ADMIN, SUPPORT)) {
            assertForbidden(() -> summaries.sales(caller, 7));
        }
        assertForbidden(() -> summaries.managed(new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of()), null, 7));
        assertForbidden(() -> summaries.sales(new CurrentUser(UUID.randomUUID(), Set.of("SELLER"), Set.of()), 7));
        assertThatThrownBy(() -> summaries.sales(null, 7)).isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(401, "COMMERCE_UNAUTHENTICATED");
    }

    private static Map<OrderStatus, Long> statuses(OrderSummaryResponse summary) {
        return summary.statuses().stream().collect(Collectors.toMap(
                OrderSummaryResponse.StatusCount::status, OrderSummaryResponse.StatusCount::count));
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DomainException.class).extracting("code", "errorCode")
                .containsExactly(403, "COMMERCE_FORBIDDEN");
    }

    private static CurrentUser user(String role) {
        return new CurrentUser(UUID.randomUUID(), Set.of(role), Set.of("ORDER.LIST"));
    }

    private void order(CurrentUser seller, OrderStatus status, String total, String createdAt) {
        number++;
        OffsetDateTime at = OffsetDateTime.parse(createdAt);
        UUID buyer = UUID.randomUUID();
        UUID groupId = jdbc().queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString(), new BigDecimal(total), at.plusMinutes(15));
        jdbc().update("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status, subtotal, total_amount,
                                    idempotency_key, payment_due_at, created_at)
                VALUES (?, ?, ?, ?, CAST('STANDARD' AS order_type), CAST(? AS order_status), ?, ?, ?, ?, ?)
                """, groupId, buyer, seller.id(), "NX-SUMMARY-%04d".formatted(number), status.name(),
                new BigDecimal(total), new BigDecimal(total), UUID.randomUUID().toString(), at.plusMinutes(15), at);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean OrderSummaryService orderSummaryService(JdbcTemplate jdbc) { return OrderSummaryTestAccess.service(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)); }

        @Bean
        DataSource dataSource() {
            String url = POSTGRES == null ? POSTGRES_URL : POSTGRES.getJdbcUrl();
            String username = POSTGRES == null ? System.getProperty("nexus.test.postgres.username", "postgres") : POSTGRES.getUsername();
            String password = POSTGRES == null ? System.getProperty("nexus.test.postgres.password", "") : POSTGRES.getPassword();
            DataSource source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=commerce_order_summary",
                    username, password);
            Flyway.configure().dataSource(source).schemas("commerce_order_summary")
                    .defaultSchema("commerce_order_summary").load().migrate();
            return source;
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }
}
