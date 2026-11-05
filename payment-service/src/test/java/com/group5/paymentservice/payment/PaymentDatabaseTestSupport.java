package com.group5.paymentservice.payment;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "spring.config.name=payment-service-test", "spring.config.import=",
        "spring.cloud.config.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false", "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false", "nexus.outbox.relay.enabled=false",
        "nexus.payment.pending-cancellations.cron=-",
        "management.tracing.export.zipkin.enabled=false", "spring.jpa.hibernate.ddl-auto=validate",
        "nexus.payment.provider=SEPAY", "nexus.payment.sepay.bank-id=970416",
        "nexus.payment.sepay.bank-account=0000000002", "nexus.payment.sepay.account-name=DO VAN A",
        "nexus.payment.sepay.vietqr-template=compact",
        "nexus.security.jwt.issuer=user-service", "nexus.security.jwt.audience=nexus-services",
        "nexus.security.jwt.jwk-set-uri=http://127.0.0.1:1/.well-known/jwks.json"
})
public abstract class PaymentDatabaseTestSupport {
    private static class ContainerHolder {
        private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
                System.getProperty("nexus.test.postgres.image", "postgres:18-alpine"));
        static { DATABASE.start(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if ("true".equals(System.getenv("PAYMENT_DB_TEST"))) {
            registry.add("spring.datasource.url", () -> required("PAYMENT_TEST_DB_URL"));
            registry.add("spring.datasource.username", () -> required("PAYMENT_TEST_DB_USERNAME"));
            registry.add("spring.datasource.password", () -> required("PAYMENT_TEST_DB_PASSWORD"));
        } else {
            registry.add("spring.datasource.url", ContainerHolder.DATABASE::getJdbcUrl);
            registry.add("spring.datasource.username", ContainerHolder.DATABASE::getUsername);
            registry.add("spring.datasource.password", ContainerHolder.DATABASE::getPassword);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required for external DB tests");
        return value;
    }
}
