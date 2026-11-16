package com.group5.ratingservice;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The optional native URL runs the identical Flyway/JPA/HTTP tests when Docker is unavailable. */
abstract class RatingPostgresSupport {
    private static final class ContainerDatabase {
        private static final PostgreSQLContainer DATABASE = start();
        private static PostgreSQLContainer start() {
            var database = new PostgreSQLContainer("postgres:18-alpine");
            database.start();
            return database;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String nativeUrl = System.getProperty("nexus.test.jdbc-url");
        if (nativeUrl != null && !nativeUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> nativeUrl);
            registry.add("spring.datasource.username", () -> System.getProperty("nexus.test.jdbc-user", "postgres"));
            registry.add("spring.datasource.password", () -> System.getProperty("nexus.test.jdbc-password", ""));
        } else {
            registry.add("spring.datasource.url", ContainerDatabase.DATABASE::getJdbcUrl);
            registry.add("spring.datasource.username", ContainerDatabase.DATABASE::getUsername);
            registry.add("spring.datasource.password", ContainerDatabase.DATABASE::getPassword);
        }
    }
}
