package com.group5.commerceservice;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.UUID;

/** Opt-in native PostgreSQL uses a fresh database per fixture; default CI still uses Testcontainers. */
public final class PostgresTestDatabase extends PostgreSQLContainer {
    private final String nativeUrl = System.getProperty("nexus.test.jdbc-url");
    private String fixtureUrl;

    public PostgresTestDatabase(String image) { super(image); }

    @Override
    public void start() {
        if (nativeUrl == null || nativeUrl.isBlank()) { super.start(); return; }
        String database = "nexus_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(nativeUrl, getUsername(), getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Cannot create isolated PostgreSQL test database", failure);
        }
        fixtureUrl = nativeUrl.substring(0, nativeUrl.lastIndexOf('/') + 1) + database + "?loggerLevel=OFF";
    }

    @Override public String getJdbcUrl() { return nativeUrl == null || nativeUrl.isBlank() ? super.getJdbcUrl() : fixtureUrl; }
    @Override public String getUsername() { return nativeUrl == null || nativeUrl.isBlank() ? super.getUsername() : System.getProperty("nexus.test.jdbc-user", "postgres"); }
    @Override public String getPassword() { return nativeUrl == null || nativeUrl.isBlank() ? super.getPassword() : System.getProperty("nexus.test.jdbc-password", ""); }
    @Override public void stop() { if (nativeUrl == null || nativeUrl.isBlank()) super.stop(); }
}
