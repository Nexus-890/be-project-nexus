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
    void nativeRepositoryServesNoTokenOrLockoutSettingsNowThatKeycloakSignsUsersIn() {
        Environment environment = environmentRepository.findOne("user-service", "default", null);

        assertThat(environment.getName()).isEqualTo("user-service");
        assertThat(environment.getPropertySources()).isNotEmpty();
        assertThat(environment.getPropertySources().stream()
                .flatMap(source -> source.getSource().keySet().stream())
                .map(Object::toString))
                .noneMatch(key -> key.startsWith("nexus.iam.jwt.")
                        || key.equals("nexus.iam.access-token-expiry-minutes")
                        || key.equals("nexus.iam.max-login-attempts")
                        || key.equals("nexus.iam.lock-duration-minutes"));
    }

    @Test
    void nativeRepositoryServesRegistrationDefaultsAndKeepsOnlyRegistrationResendAndPasswordResetPublic() {
        Environment environment = environmentRepository.findOne("user-service", "default", null);

        assertThat(propertyValue(environment, "nexus.iam.default-user-role")).isEqualTo("BUYER");
        assertThat(propertyValue(environment, "nexus.iam.allow-self-registration")).isEqualTo("true");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[0].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[0].path"))
                .isEqualTo("/api/v1/auth/register");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[1].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[1].path"))
                .isEqualTo("/api/v1/auth/verification-email");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[2].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[2].path"))
                .isEqualTo("/api/v1/auth/password-reset");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[3].method")).isEqualTo("POST");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[3].path"))
                .isEqualTo("/api/v1/auth/password-reset/confirm");
        assertThat(propertyValue(environment, "nexus.security.public-endpoints[4].path")).isNull();
        assertThat(environment.getPropertySources().stream()
                .flatMap(source -> source.getSource().values().stream())
                .map(Object::toString))
                .doesNotContain("/api/v1/users/me", "/api/v1/auth/login", "/.well-known/jwks.json");
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
