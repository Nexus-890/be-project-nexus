package com.group5.inventoryservice.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerOpeningBalanceMigrationTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static { POSTGRES.start(); }

    private final DriverManagerDataSource dataSource =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    @BeforeEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void stockTakenInBeforeTheUpgradeGetsOneOpeningIntakeSoTheSumMatchesTheTotal() {
        flyway("6").migrate();
        UUID admin = UUID.randomUUID();
        UUID warehouse = jdbc.queryForObject(
                "INSERT INTO warehouses (code, name) VALUES ('WH_UP', 'Upgrade') RETURNING id", UUID.class);
        UUID untracked = balance(warehouse, 100, admin);
        UUID partlyTracked = balance(warehouse, 80, admin);
        UUID empty = balance(warehouse, 0, admin);
        jdbc.update("""
                INSERT INTO inventory_movements (sku_id, warehouse_id, movement_type, quantity, reference_id)
                SELECT sku_id, warehouse_id, 'INTAKE', 30, id FROM inventory_balances WHERE id = ?
                """, partlyTracked);

        flyway(null).migrate();

        assertThat(intakes(untracked)).singleElement().satisfies(row -> {
            assertThat(row.get("quantity")).isEqualTo(100);
            assertThat(row.get("reference_id")).isEqualTo(untracked);
            assertThat(row.get("created_by")).isEqualTo(admin);
        });
        assertThat(intakes(partlyTracked)).extracting(row -> row.get("quantity")).containsExactlyInAnyOrder(30, 50);
        assertThat(intakes(empty)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_balances b
                WHERE b.total_quantity <> (SELECT COALESCE(SUM(m.quantity), 0) FROM inventory_movements m
                                           WHERE m.sku_id = b.sku_id AND m.warehouse_id = b.warehouse_id
                                             AND m.movement_type = 'INTAKE')
                """, Integer.class)).isZero();
    }

    @Test
    void aFreshDatabaseGetsNoOpeningRows() {
        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class)).isZero();
    }

    private UUID balance(UUID warehouse, int quantity, UUID actor) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_balances
                    (sku_id, warehouse_id, total_quantity, available_quantity, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, UUID.randomUUID(), warehouse, quantity, quantity, actor, actor);
    }

    private List<Map<String, Object>> intakes(UUID balanceId) {
        return jdbc.queryForList("""
                SELECT m.quantity, m.reference_id, m.created_by FROM inventory_movements m
                JOIN inventory_balances b ON b.sku_id = m.sku_id AND b.warehouse_id = m.warehouse_id
                WHERE b.id = ? AND m.movement_type = 'INTAKE'
                """, balanceId);
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
