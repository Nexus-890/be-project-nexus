package com.group5.acceptance;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

/** Each service gets a fresh real PostgreSQL database and its own migration directory. */
public final class AcceptanceDatabase {
    private static final String NATIVE_URL = System.getProperty("nexus.test.jdbc-url");
    private static final class Container {
        private static final PostgreSQLContainer DATABASE = start();
        private static PostgreSQLContainer start() {
            var postgres = new PostgreSQLContainer("postgres:18");
            postgres.start();
            return postgres;
        }
    }

    public static DataSource create(String service) {
        String source = NATIVE_URL == null || NATIVE_URL.isBlank() ? Container.DATABASE.getJdbcUrl() : NATIVE_URL;
        String username = NATIVE_URL == null || NATIVE_URL.isBlank() ? Container.DATABASE.getUsername() : System.getProperty("nexus.test.jdbc-user", "postgres");
        String password = NATIVE_URL == null || NATIVE_URL.isBlank() ? Container.DATABASE.getPassword() : System.getProperty("nexus.test.jdbc-password", "");
        String database = "receipt_" + service.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(source, username, password); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot create acceptance database", failure); }
        var dataSource = new DriverManagerDataSource(source.substring(0, source.lastIndexOf('/') + 1) + database, username, password);
        Path migrations = Path.of(System.getProperty("nexus.backend"), service, "src/main/resources/db/migration").toAbsolutePath().normalize();
        Flyway.configure().dataSource(dataSource).locations("filesystem:" + migrations.toString().replace('\\', '/')).load().migrate();
        return dataSource;
    }

    private AcceptanceDatabase() { }
}
