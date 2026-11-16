package com.group5.userservice.seller;

import com.group5.userservice.shared.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class SellerMigrationTest {
    @Test
    void upgradingV7PreservesExistingUserIdentitiesAndDoesNotBackfillAcrossServices() {
        var source = PostgresTestDatabase.dataSource("seller_upgrade");
        Flyway.configure().dataSource(source).schemas("seller_upgrade").defaultSchema("seller_upgrade")
                .target("7").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        Flyway.configure().dataSource(source).schemas("seller_upgrade").defaultSchema("seller_upgrade").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema='seller_upgrade' AND table_name='seller' AND column_name='user_id'", String.class)).isNull();
    }
}
