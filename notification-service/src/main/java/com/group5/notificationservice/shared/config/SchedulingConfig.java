package com.group5.notificationservice.shared.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulingConfig {

    // KHÓA JOB NẰM CÙNG REDIS VỚI TRẠNG THÁI HEALTH -> POSTGRES LỖI VẪN CẬP NHẬT ĐƯỢC TRẠNG THÁI BROKER
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory, HealthProperties properties) {
        return new RedisLockProvider(connectionFactory, properties.environment());
    }
}
