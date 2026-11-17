package com.group5.notificationservice.delivery;

import com.group5.notificationservice.shared.config.DeliveryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.core.env.MapPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyRefreshTest {
    /** Worker đang tồn tại dùng limit mới ngay lượt tiếp theo mà context không restart. */
    @Test
    void existingRetryPolicyUsesRefreshedLimit() {
        Map<String, Object> changed = new HashMap<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                        ValidationAutoConfiguration.class, RefreshAutoConfiguration.class))
                .withUserConfiguration(DeliveryProperties.class, RetryPolicy.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("config-server-update", changed)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RetryPolicy policy = context.getBean(RetryPolicy.class);
                    Instant now = Instant.parse("2026-11-14T07:00:00Z");
                    assertThat(policy.limit()).isEqualTo(3);
                    assertThat(policy.nextAttemptAt(3, true, now)).isEmpty();
                    changed.put("nexus.notification.delivery.retry-limit", 4);
                    context.getBean(RefreshScope.class).refreshAll();
                    assertThat(context.getBean(RetryPolicy.class)).isSameAs(policy);
                    assertThat(policy.limit()).isEqualTo(4);
                    assertThat(policy.nextAttemptAt(3, true, now)).isPresent();
                    changed.put("nexus.notification.delivery.retry-limit", 2);
                    context.getBean(RefreshScope.class).refreshAll();
                    assertThat(policy.nextAttemptAt(2, true, now)).isEmpty();
                });
    }
}
