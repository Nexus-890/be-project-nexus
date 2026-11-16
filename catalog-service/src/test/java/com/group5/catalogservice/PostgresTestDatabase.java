package com.group5.catalogservice;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

/** Opt-in native PostgreSQL keeps real persistence assertions and a fresh database per fixture. */
public final class PostgresTestDatabase extends PostgreSQLContainer {
    private final String nativeUrl = System.getProperty("nexus.test.jdbc-url");
    private String fixtureUrl;

    public PostgresTestDatabase(String image) { super(image); }

    @Override
    public void start() {
        if (!nativeMode()) { super.start(); return; }
        fixtureUrl = createDatabase("nexus_catalog_");
    }

    public String createDatabase(String prefix) {
        String database = prefix + UUID.randomUUID().toString().replace("-", "");
        String source = nativeMode() ? nativeUrl : getJdbcUrl();
        try (var connection = DriverManager.getConnection(source, getUsername(), getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot create isolated PostgreSQL test database", failure);
        }
        return source.substring(0, source.lastIndexOf('/') + 1) + database;
    }

    private boolean nativeMode() { return nativeUrl != null && !nativeUrl.isBlank(); }

    @Override public String getJdbcUrl() { return nativeMode() ? fixtureUrl : super.getJdbcUrl(); }
    @Override public String getUsername() { return nativeMode() ? System.getProperty("nexus.test.jdbc-user", "postgres") : super.getUsername(); }
    @Override public String getPassword() { return nativeMode() ? System.getProperty("nexus.test.jdbc-password", "") : super.getPassword(); }
    @Override public void stop() { if (!nativeMode()) super.stop(); }
}
