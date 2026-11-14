package com.group5.commerceservice.order;

import com.group5.commerceservice.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Upgrade the latest develop and the shared settlement index before the receipt migrations. */
class ReceiptMigrationCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"12", "13", "14"})
    void settlementAndReceiptMigrationsUpgradeTogetherWithoutLosingDeliveredOrders(String baseline) throws Exception {
        var database = new PostgresTestDatabase("postgres:18-alpine");
        database.start();
        try {
            Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .target(baseline).load().migrate();
            try (var connection = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
                 var statement = connection.createStatement()) {
                UUID buyer = UUID.randomUUID();
                UUID seller = UUID.randomUUID();
                UUID group = UUID.randomUUID();
                UUID order = UUID.randomUUID();
                UUID auction = UUID.randomUUID();
                statement.executeUpdate("INSERT INTO order_groups(id,buyer_id,idempotency_key,total_amount,due_at) VALUES ('"
                        + group + "','" + buyer + "','migration-group',100,now()+interval '1 day')");
                String insert = "INSERT INTO orders(id,order_group_id,user_id,seller_id,order_number,order_type,reference_id,status,subtotal,total_amount,idempotency_key) VALUES ";
                statement.executeUpdate(insert + "('" + order + "','" + group + "','" + buyer + "','" + seller
                        + "','NX-MIGRATION-220','AUCTION','" + auction + "','DELIVERED',100,100,'migration-order')");

                var flyway = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()).load();
                flyway.migrate();
                flyway.validate();

                try (var migrated = statement.executeQuery("SELECT status::text,auto_complete_at-delivered_at AS window,receipt_issue_reported_at FROM orders WHERE id='" + order + "'")) {
                    assertThat(migrated.next()).isTrue();
                    assertThat(migrated.getString("status")).isEqualTo("DELIVERED");
                    assertThat(migrated.getString("window")).isEqualTo("7 days");
                    assertThat(migrated.getObject("receipt_issue_reported_at")).isNull();
                }
                try (var versions = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE version IN ('13','14','15','16') AND success ORDER BY installed_rank")) {
                    var found = new java.util.ArrayList<String>();
                    while (versions.next()) found.add(versions.getString(1));
                    assertThat(found).containsExactly("13", "14", "15", "16");
                }
                assertThatThrownBy(() -> statement.executeUpdate(insert + "('" + UUID.randomUUID() + "','" + group
                        + "','" + buyer + "','" + seller + "','NX-MIGRATION-DUPLICATE','AUCTION','" + auction
                        + "','PENDING',100,100,'migration-duplicate')"))
                        .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("23505");
            }
        } finally { database.stop(); }
    }
}
