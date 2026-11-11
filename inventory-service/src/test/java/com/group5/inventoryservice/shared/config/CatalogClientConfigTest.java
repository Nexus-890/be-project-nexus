package com.group5.inventoryservice.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CatalogClientConfigTest {
    @Test
    void literalEurekaHttpAndServiceDiscoveryCatalogUseDifferentBuilders() {
        try (var context = new AnnotationConfigApplicationContext(CatalogClientConfig.class, Consumers.class)) {
            var clients = context.getBean(Clients.class);
            assertThat(clients.literal).isSameAs(context.getBean("restClientBuilder"));
            assertThat(clients.catalog).isSameAs(context.getBean("loadBalancedRestClientBuilder"));
            assertThat(clients.literal).isNotSameAs(clients.catalog);
            var server = MockRestServiceServer.bindTo(clients.literal).build();
            server.expect(requestTo("http://localhost:8761/eureka/apps")).andRespond(withSuccess());
            clients.literal.build().get().uri("http://localhost:8761/eureka/apps").retrieve().toBodilessEntity();
            server.verify();
        }
    }

    @Configuration
    static class Consumers {
        @Bean
        Clients clients(RestClient.Builder literal, @LoadBalanced RestClient.Builder catalog) {
            return new Clients(literal, catalog);
        }
    }

    static class Clients {
        final RestClient.Builder literal;
        final RestClient.Builder catalog;
        Clients(RestClient.Builder literal, RestClient.Builder catalog) { this.literal = literal; this.catalog = catalog; }
    }
}
