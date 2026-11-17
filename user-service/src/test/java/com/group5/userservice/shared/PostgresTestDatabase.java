package com.group5.userservice.shared;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;

/** A disposable PostgreSQL 18 instance shared by the test JVM; each context owns a schema. */
public final class PostgresTestDatabase {
    private static final String EXTERNAL_URL = System.getProperty("admin.test.jdbc-url");

    /** Chỉ khởi động Docker khi không truyền JDBC database test riêng. */
    private static final class ContainerHolder {
        private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

        static {
            POSTGRES.start();
        }
    }

    private PostgresTestDatabase() {
    }

    public static String jdbcUrl() {
        return EXTERNAL_URL != null ? EXTERNAL_URL : ContainerHolder.POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return EXTERNAL_URL != null ? System.getProperty("admin.test.username", "admin211")
                : ContainerHolder.POSTGRES.getUsername();
    }

    public static String password() {
        return EXTERNAL_URL != null ? System.getProperty("admin.test.password", "")
                : ContainerHolder.POSTGRES.getPassword();
    }

    public static DataSource migratedDataSource(String schema) {
        DataSource source = dataSource(schema);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        return source;
    }

    public static DataSource dataSource(String schema) {
        if (!schema.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Invalid test schema");
        }
        String separator = jdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                jdbcUrl() + separator + "currentSchema=" + schema, username(), password());
    }
}
