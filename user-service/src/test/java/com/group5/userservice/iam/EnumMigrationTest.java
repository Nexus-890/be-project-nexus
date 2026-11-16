package com.group5.userservice.iam;

import com.group5.common_security.CurrentUserAuditor;
import com.group5.userservice.shared.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumMigrationTest {
    @Test
    void upgradesSeededVarcharValuesWithoutChangingTheirMeaning() {
        DataSource source = PostgresTestDatabase.dataSource("enum_upgrade");
        Flyway.configure().dataSource(source).schemas("enum_upgrade").defaultSchema("enum_upgrade")
                .target("3").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("update users set status = 'LOCKED', gender = 'FEMALE' where email = 'seller@nexus.local'");
        jdbc.update("update users set status = 'DELETED', gender = 'MALE' where email = 'buyer@nexus.local'");
        jdbc.update("update users set gender = 'OTHER' where email = 'admin@nexus.local'");
        jdbc.update("""
                insert into user_reputations(user_id, trust_level)
                select id, case email when 'admin@nexus.local' then 'TOP'
                                     when 'seller@nexus.local' then 'TRUSTED' else 'BASIC' end
                from users
                """);
        List<Map<String, Object>> users = jdbc.queryForList("select id, status, gender from users order by id");
        List<Map<String, Object>> reputations = jdbc.queryForList(
                "select id, trust_level from user_reputations order by id");

        Flyway.configure().dataSource(source).schemas("enum_upgrade").defaultSchema("enum_upgrade")
                .target("5").load().migrate();

        assertThat(jdbc.queryForList("select id, status::text as status, gender::text as gender from users order by id"))
                .isEqualTo(users);
        assertThat(jdbc.queryForList("select id, trust_level::text as trust_level from user_reputations order by id"))
                .isEqualTo(reputations);
        assertThat(jdbc.queryForList("select created_by from users", java.util.UUID.class))
                .containsOnly(CurrentUserAuditor.SYSTEM_ID);
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name || ':' || udt_name
                from information_schema.columns
                where table_schema = 'enum_upgrade' and data_type = 'USER-DEFINED'
                """, String.class)).containsExactlyInAnyOrder(
                "users.status:user_status", "users.gender:gender", "user_reputations.trust_level:trust_level");
    }

    @Test
    void freshSchemaHasNamedEnumsRejectsInvalidValuesAndKeepsDatabaseDefaults() {
        JdbcTemplate jdbc = new JdbcTemplate(PostgresTestDatabase.migratedDataSource("enum_fresh"));
        verifyEnumColumns(jdbc, "enum_fresh");
        assertThatThrownBy(() -> jdbc.update("update users set status = 'INVALID'"))
                .rootCause().hasMessageContaining("invalid input value for enum user_status");
        assertThatThrownBy(() -> jdbc.update("update users set gender = 'INVALID'"))
                .rootCause().hasMessageContaining("invalid input value for enum gender");
        assertThat(jdbc.queryForObject("select to_regclass('enum_fresh.user_reputations')", String.class)).isNull();
        assertThat(jdbc.queryForObject("select to_regclass('enum_fresh.ratings')", String.class)).isNull();
        assertThat(jdbc.queryForList("select distinct status::text from users", String.class)).containsExactly("ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'enum_fresh' and column_name in ('created_by', 'updated_by')
                  and table_name = 'users'
                  and data_type = 'uuid' and is_nullable = 'NO'
                """, Integer.class)).isEqualTo(2);
    }

    private static void verifyEnumColumns(JdbcTemplate jdbc, String schema) {
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name || ':' || udt_name
                from information_schema.columns
                where table_schema = ? and data_type = 'USER-DEFINED'
                """, String.class, schema)).containsExactlyInAnyOrder(
                "users.status:user_status", "users.gender:gender", "seller.status:seller_status");
    }
}
