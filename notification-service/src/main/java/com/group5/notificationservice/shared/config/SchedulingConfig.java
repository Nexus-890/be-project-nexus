package com.group5.notificationservice.shared.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulingConfig {

    public static final String HEALTH_SCHEDULER = "healthScheduler";

    // CÓ THÊM healthScheduler THÌ BOOT KHÔNG TỰ TẠO SCHEDULER MẶC ĐỊNH NỮA -> KHAI BÁO LẠI CHO CÁC JOB KHÁC (EMAIL)
    @Primary
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    // THREAD RIÊNG CHO KIỂM TRA KAFKA -> JOB EMAIL KẸT (VÍ DỤ claimNext CHỜ DB) KHÔNG CHẶN LỊCH HEALTH
    @Bean(name = HEALTH_SCHEDULER)
    public ThreadPoolTaskScheduler healthScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("health-scheduling-").build();
    }

    // KHÓA JOB NẰM CÙNG REDIS VỚI TRẠNG THÁI HEALTH -> POSTGRES LỖI VẪN CẬP NHẬT ĐƯỢC TRẠNG THÁI BROKER
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory, HealthProperties properties) {
        return new RedisLockProvider(connectionFactory, properties.environment());
    }
}
