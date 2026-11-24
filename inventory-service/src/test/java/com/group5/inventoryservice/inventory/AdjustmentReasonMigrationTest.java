package com.group5.inventoryservice.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdjustmentReasonMigrationTest {
    private static final PostgreSQLContainer POSTGRES = new com.group5.common_test.PostgresTestDatabase("postgres:18");
    static { POSTGRES.start(); }

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @BeforeEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void adjustmentRowsWrittenBeforeTheUpgradeStayInTheLedgerAndNewOnesNeedAReason() {
        flyway("8").migrate();
        UUID warehouse = jdbc.queryForObject(
                "INSERT INTO warehouses (code, name) VALUES ('WH_ADJ', 'Upgrade') RETURNING id", UUID.class);
        UUID sku = UUID.randomUUID();
        UUID counted = movement(sku, warehouse, "ADJUSTMENT", -3);
        UUID empty = movement(sku, warehouse, "ADJUSTMENT", 0);
        movement(sku, warehouse, "INTAKE", 10);

        flyway(null).migrate();

        assertThat(jdbc.queryForList("SELECT id FROM inventory_movements WHERE sku_id = ? AND reason IS NULL",
                UUID.class, sku)).contains(counted, empty).hasSize(3);
        assertThat(jdbc.queryForObject("""
                SELECT sum(quantity)::int FROM inventory_movements WHERE sku_id = ? AND warehouse_id = ?
                """, Integer.class, sku, warehouse)).isEqualTo(7);
        assertThatThrownBy(() -> movement(sku, warehouse, "ADJUSTMENT", 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reason)
                VALUES (?, ?, 'ADJUSTMENT', 0, 'Recount')
                """, sku, warehouse)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reason)
                VALUES (?, ?, 'ADJUSTMENT', 2, 'Recount')
                """, sku, warehouse);
    }

    private UUID movement(UUID sku, UUID warehouse, String type, int quantity) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity)
                VALUES (?, ?, ?::inventory_movement_type, ?) RETURNING id
                """, UUID.class, sku, warehouse, type, quantity);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
