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
