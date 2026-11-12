package com.group5.common_test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL database shared by Spring integration tests in one JVM.
 * Consumers apply their own Flyway migrations and isolate their fixtures.
 * Docker is required: a missing daemon fails the test instead of skipping it.
 */
public abstract class PostgresIntegrationTest {
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withEnv("TZ", "UTC")
            .withUrlParam("options", "-c timezone=UTC");

    static {
        // Keep the container alive for cached Spring contexts; Ryuk cleans it up with the JVM.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }
}
