package com.group5.userservice.iam;

import com.group5.userservice.shared.exception.UserErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.filter.TraceIdFilter;
import com.group5.userservice.iam.dto.RegisterRequest;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real embedded HTTP server and Boot's inherited Brave/Micrometer tracing configuration.
 * The service is mocked only to trigger the identity-provider error; tracing is never mocked.
 */
class AuthTracingHttpTest {

    @Test
    void anIdentityProviderOutageReturnsAProblemWithTheRealHttpRequestTraceId() throws Exception {
        SpringApplication application = new SpringApplication(TestApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.name=auth-tracing-http-test",
                "--spring.config.import=",
                "--spring.cloud.config.enabled=false",
                "--spring.cloud.compatibility-verifier.enabled=false",
                "--spring.cloud.discovery.enabled=false",
                "--eureka.client.enabled=false",
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--management.tracing.sampling.probability=1.0",
                "--management.tracing.export.zipkin.enabled=false",
                "--spring.main.banner-mode=off")) {

            Tracer tracer = context.getBean(Tracer.class);
            UserService userService = context.getBean(UserService.class);
            AtomicReference<String> requestTraceId = new AtomicReference<>();
            when(userService.register(any(RegisterRequest.class))).thenAnswer(invocation -> {
                Span span = tracer.currentSpan();
                assertThat(span).as("HTTP observation must create an active tracing span").isNotNull();
                requestTraceId.set(span.context().traceId());
                throw new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE);
            });

            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/api/v1/auth/register"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"email":"buyer@nexus.local","phone":"0901234567","password":"correct-password",
                             "fullName":"Buyer One","identityNumber":"ID123456","gender":"MALE",
                             "address":"1 Main Street","dateOfBirth":"2000-01-01"}
                            """))
                    .build();
            try (HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(503);
                MediaType responseType = MediaType.parseMediaType(
                        response.headers().firstValue("Content-Type").orElseThrow());
                assertThat(responseType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();

                var problem = JsonPath.parse(response.body());
                assertThat(problem.read("$.type", String.class)).isEqualTo("urn:problem:domain-error");
                assertThat(problem.read("$.title", String.class)).isEqualTo("Service Unavailable");
                assertThat(problem.read("$.status", Integer.class)).isEqualTo(503);
                assertThat(problem.read("$.code", String.class)).isEqualTo("USER_IDENTITY_PROVIDER_UNAVAILABLE");
                assertThat(problem.read("$.detail", String.class)).isEqualTo("The identity provider is unavailable");
                assertThat(problem.read("$.instance", String.class)).isEqualTo("/api/v1/auth/register");
                assertThat(requestTraceId.get()).matches("[0-9a-f]{32}");
                assertThat(problem.read("$.traceId", String.class)).isEqualTo(requestTraceId.get());
                assertThat(response.headers().firstValue(TraceIdFilter.TRACE_ID_HEADER))
                        .contains(requestTraceId.get());
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataJpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            KafkaAutoConfiguration.class
    })
    static class TestApplication {

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        AuthController authController(UserService userService) {
            return new AuthController(userService, mock(EmailVerificationService.class));
        }
    }
}
