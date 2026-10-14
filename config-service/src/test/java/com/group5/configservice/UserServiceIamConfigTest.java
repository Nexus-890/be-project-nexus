package com.group5.configservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ConfigServiceApplication.class)
class UserServiceIamConfigTest {

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Test
    void nativeRepositoryServesTheUserServiceLockoutSettings() {
        Environment environment = environmentRepository.findOne("user-service", "default", null);

        assertThat(environment.getName()).isEqualTo("user-service");
        assertThat(environment.getPropertySources()).isNotEmpty();
        assertThat(propertyValue(environment, "nexus.iam.max-login-attempts")).isEqualTo("5");
        assertThat(propertyValue(environment, "nexus.iam.lock-duration-minutes")).isEqualTo("15");
    }

    @Test
    void nativeRepositoryServesRegistrationDefaultsAndKeepsOnlyAuthEndpointsPublic() {
        Environment environment = environmentRepository.findOne("user-service", "default", null);

        assertThat(propertyValue(environment, "nexus.iam.default-user-role")).isEqualTo("BUYER");
        assertThat(propertyValue(environment, "nexus.iam.allow-self-registration")).isEqualTo("true");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[0].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[0].path"))
                .isEqualTo("/api/v1/auth/login");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[1].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[1].path"))
                .isEqualTo("/api/v1/auth/register");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[2].method")).isEqualTo("GET");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[2].path"))
                .isEqualTo("/.well-known/jwks.json");
        assertThat(environment.getPropertySources().stream()
                .flatMap(source -> source.getSource().values().stream())
                .map(Object::toString))
                .doesNotContain("/api/v1/users/me");
    }

    private static String propertyValue(Environment environment, String key) {
        // Config Server orders sources by precedence, just as its clients receive them.
        return environment.getPropertySources().stream()
                .map(source -> source.getSource().get(key))
                .filter(Objects::nonNull)
                .findFirst()
                .map(Object::toString)
                .orElse(null);
    }
}
