package com.group5.auctionservice;

import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Docker by default; an optional local PG18 admin URL creates a disposable database per fixture. */
public final class TestPostgres {
    private PostgreSQLContainer container;
    private String adminUrl;
    private String database;
    private String url;
    private String username;
    private String password;

    public synchronized void start() {
        if (url != null) return;
        adminUrl = System.getenv("TEST_POSTGRES_ADMIN_URL");
        if (adminUrl == null || adminUrl.isBlank()) {
            container = new PostgreSQLContainer("postgres:18");
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        } else {
            username = System.getenv().getOrDefault("TEST_POSTGRES_USERNAME", "postgres");
            password = System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", "");
            database = "auction_test_" + UUID.randomUUID().toString().replace("-", "");
            executeAdmin("CREATE DATABASE " + database);
            url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    public synchronized void stop() {
        if (url == null) return;
        if (container != null) container.stop();
        else executeAdmin("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        url = null;
    }

    private void executeAdmin(String sql) {
        try (var connection = DriverManager.getConnection(adminUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException("Disposable PostgreSQL fixture failed", failure);
        }
    }

    public String getJdbcUrl() { return url; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
}
