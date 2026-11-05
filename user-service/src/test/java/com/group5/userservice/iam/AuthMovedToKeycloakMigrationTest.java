package com.group5.userservice.iam;

import com.group5.userservice.shared.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthMovedToKeycloakMigrationTest {
    @Test
    void profilesKeepTheirRowsWhileCredentialsRolesAndTokensLeaveUserDb() {
        DataSource source = PostgresTestDatabase.dataSource("auth_moved");
        Flyway.configure().dataSource(source).schemas("auth_moved").defaultSchema("auth_moved")
                .target("6").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                insert into refresh_tokens(user_id, token_hash, expires_at)
                select id, md5(email), now() + interval '1 day' from users
                """);
        String profiles = """
                select id, email, phone, full_name, identity_number, status::text as status, version
                from users order by id
                """;
        List<Map<String, Object>> before = jdbc.queryForList(profiles);

        Flyway.configure().dataSource(source).schemas("auth_moved").defaultSchema("auth_moved")
                .load().migrate();

        assertThat(before).hasSize(3);
        assertThat(jdbc.queryForList(profiles)).isEqualTo(before);
        assertThat(jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'auth_moved' and table_name = 'users'
                  and column_name in ('password_hash', 'failed_login_count', 'locked_until', 'last_login_at')
                """, String.class)).isEmpty();
        for (String table : List.of("refresh_tokens", "password_reset_tokens", "user_roles",
                "role_permissions", "roles", "permissions")) {
            assertThat(jdbc.queryForObject("select to_regclass(?)", String.class, "auth_moved." + table))
                    .as(table).isNull();
        }
    }
}
