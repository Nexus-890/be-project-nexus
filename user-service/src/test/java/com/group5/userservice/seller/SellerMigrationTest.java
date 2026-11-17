package com.group5.userservice.seller;

import com.group5.userservice.shared.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SellerMigrationTest {
    private static final List<String> DEVELOP_VERSIONS = List.of("1", "3", "4", "5", "6", "7", "8");

    @Test
    void upgradingV7PreservesExistingUserIdentitiesAndDoesNotBackfillAcrossServices() {
        var source = PostgresTestDatabase.dataSource("seller_upgrade");
        Flyway.configure().dataSource(source).schemas("seller_upgrade").defaultSchema("seller_upgrade")
                .target("7").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        flyway(source, "seller_upgrade", "9").migrate();
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema='seller_upgrade' AND table_name='seller' AND column_name='user_id'", String.class)).isNull();
    }

    /** Chuỗi mới phải giữ V8 seller và thêm journal V9, không bỏ sót version lịch sử. */
    @Test
    void freshSchemaIncludesSellerAndIdentityJournalWithoutDuplicateVersions() {
        var source = PostgresTestDatabase.dataSource("seller_journal_fresh");
        var flyway = flyway(source, "seller_journal_fresh");
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);
        flyway.validate();
        var jdbc = new JdbcTemplate(source);
        assertThat(versions(jdbc)).containsExactly("1", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        verifySellerAndJournal(jdbc);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
    }

    /** V1–V8 giữ nguyên byte từ develop; nâng cấp phải giữ mọi cột dữ liệu và lịch sử checksum. */
    @Test
    void upgradingDevelopV8PreservesUsersSellersAndFullFlywayHistory() {
        String schema = "seller_journal_upgrade";
        var source = PostgresTestDatabase.dataSource(schema);
        var baselineFlyway = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .target("8").load();
        assertThat(baselineFlyway.migrate().migrationsExecuted).isEqualTo(DEVELOP_VERSIONS.size());
        baselineFlyway.validate();
        var jdbc = new JdbcTemplate(source);
        assertThat(versions(jdbc)).containsExactlyElementsOf(DEVELOP_VERSIONS);
        assertThat(jdbc.queryForObject("SELECT to_regclass(current_schema() || '.admin_identity_operations')",
                String.class)).isNull();
        jdbc.update("""
                INSERT INTO seller(user_id, company_name, contact_phone, status, application_note,
                                   reviewed_by, reviewed_at)
                SELECT id, 'Existing develop seller', '0901000002', 'ACTIVE', 'Preserve before journal upgrade',
                       '68000000-0000-4000-8000-000000000001'::uuid, '2026-11-15T01:02:03Z'::timestamptz
                FROM users WHERE email = 'seller@nexus.local'
                """);
        var usersBefore = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        var sellersBefore = jdbc.queryForList("SELECT * FROM seller ORDER BY user_id");
        var historyBefore = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(usersBefore).hasSize(3);
        assertThat(sellersBefore).hasSize(1);

        var upgraded = flyway(source, schema, "9");
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        assertThat(versions(jdbc)).containsExactly("1", "3", "4", "5", "6", "7", "8", "9");
        assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(jdbc.queryForList("SELECT * FROM seller ORDER BY user_id")).isEqualTo(sellersBefore);
        var historyAfter = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(historyAfter).hasSize(historyBefore.size() + 1);
        assertThat(historyAfter.subList(0, historyBefore.size())).containsExactlyElementsOf(historyBefore);
        assertThat(historyAfter.getLast()).containsEntry("version", "9")
                .containsEntry("script", "V9__admin_identity_operation_journal.sql").containsEntry("success", true);
        verifySellerAndJournal(jdbc);
        assertThat(upgraded.migrate().migrationsExecuted).isZero();
        upgraded.validate();
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
                .isEqualTo(historyAfter);
    }

    private static Flyway flyway(DataSource source, String schema) {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
    }

    private static Flyway flyway(DataSource source, String schema, String target) {
        return Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target(target).load();
    }

    private static List<String> versions(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank",
                String.class);
    }

    private static void verifySellerAndJournal(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name IN ('seller', 'admin_identity_operations')
                ORDER BY table_name
                """, String.class)).containsExactly("admin_identity_operations", "seller");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_identity_operations", Long.class)).isZero();
    }
}
