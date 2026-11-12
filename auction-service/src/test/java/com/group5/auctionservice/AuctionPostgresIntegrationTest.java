package com.group5.auctionservice;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same disposable PG18 fixture as the HTTP/read tests, with UTC for concurrency assertions. */
public abstract class AuctionPostgresIntegrationTest {
    private static final TestPostgres POSTGRES = new TestPostgres();
    static { POSTGRES.start(); }
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.jdbc.time_zone", () -> "UTC");
    }
}
