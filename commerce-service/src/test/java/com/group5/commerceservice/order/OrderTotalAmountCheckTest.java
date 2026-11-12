package com.group5.commerceservice.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTotalAmountCheckTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void anOrderWhoseTotalMatchesSubtotalMinusDiscountPlusTaxPlusShippingIsStored() {
        UUID order = insertOrder("300000.00", "20000.00", "15000.00", "30000.00", "325000.00");

        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class, order))
                .isEqualByComparingTo("325000.00");
    }

    @Test
    void anOrderWhoseTotalDoesNotMatchTheFormulaIsRejected() {
        assertThatThrownBy(() -> insertOrder("300000.00", "20000.00", "15000.00", "30000.00", "300000.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_orders_total_amount");
    }

    @Test
    void changingTheShippingFeeWithoutTheTotalIsRejected() {
        UUID order = insertOrder("300000.00", "0.00", "0.00", "0.00", "300000.00");

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET shipping_fee = 30000.00 WHERE id = ?", order))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_orders_total_amount");
    }

    private static UUID insertOrder(String subtotal, String discount, String tax, String shipping, String total) {
        UUID buyer = UUID.randomUUID();
        UUID group = jdbc.queryForObject("""
                INSERT INTO order_groups (buyer_id, idempotency_key, total_amount, due_at)
                VALUES (?, ?, ?, now() + interval '15 minutes') RETURNING id
                """, UUID.class, buyer, UUID.randomUUID().toString(), new BigDecimal(total));
        return jdbc.queryForObject("""
                INSERT INTO orders (order_group_id, user_id, seller_id, order_number, order_type, status,
                                    subtotal, discount_amount, tax_amount, shipping_fee, total_amount,
                                    idempotency_key)
                VALUES (?, ?, ?, ?, 'STANDARD', 'PENDING', ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, group, buyer, UUID.randomUUID(), "NX-TOTAL-" + UUID.randomUUID().toString()
                        .substring(0, 8), new BigDecimal(subtotal), new BigDecimal(discount), new BigDecimal(tax),
                new BigDecimal(shipping), new BigDecimal(total), UUID.randomUUID().toString());
    }
}
