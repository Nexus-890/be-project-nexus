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
                .load().migrate();

        assertThat(jdbc.queryForList("select id, status::text as status, gender::text as gender from users order by id"))
                .isEqualTo(users);
        assertThat(jdbc.queryForList("select id, trust_level::text as trust_level from user_reputations order by id"))
                .isEqualTo(reputations);
        assertThat(jdbc.queryForList("select created_by from users", java.util.UUID.class))
                .containsOnly(CurrentUserAuditor.SYSTEM_ID);
        verifyEnumColumns(jdbc, "enum_upgrade");
    }

    @Test
    void freshSchemaHasNamedEnumsRejectsInvalidValuesAndKeepsDatabaseDefaults() {
        JdbcTemplate jdbc = new JdbcTemplate(PostgresTestDatabase.migratedDataSource("enum_fresh"));
        verifyEnumColumns(jdbc, "enum_fresh");
        assertThatThrownBy(() -> jdbc.update("update users set status = 'INVALID'"))
                .rootCause().hasMessageContaining("invalid input value for enum user_status");
        assertThatThrownBy(() -> jdbc.update("update users set gender = 'INVALID'"))
                .rootCause().hasMessageContaining("invalid input value for enum gender");
        assertThatThrownBy(() -> jdbc.update("""
                insert into user_reputations(user_id, trust_level) select id, 'INVALID' from users limit 1
                """))
                .rootCause().hasMessageContaining("invalid input value for enum trust_level");
        jdbc.update("insert into user_reputations(user_id) select id from users limit 1");
        assertThat(jdbc.queryForObject("select trust_level::text from user_reputations", String.class)).isEqualTo("NEW");
        assertThat(jdbc.queryForList("select distinct status::text from users", String.class)).containsExactly("ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'enum_fresh' and column_name in ('created_by', 'updated_by')
                  and table_name in ('users', 'refresh_tokens', 'password_reset_tokens', 'user_reputations', 'ratings')
                  and data_type = 'uuid' and is_nullable = 'NO'
                """, Integer.class)).isEqualTo(10);
    }

    private static void verifyEnumColumns(JdbcTemplate jdbc, String schema) {
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name || ':' || udt_name
                from information_schema.columns
                where table_schema = ? and data_type = 'USER-DEFINED'
                """, String.class, schema)).containsExactlyInAnyOrder(
                "users.status:user_status", "users.gender:gender", "user_reputations.trust_level:trust_level");
    }
}
