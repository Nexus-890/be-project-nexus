package com.group5.userservice.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    @Bean //FOR JwtIssuer instead of Instant.now(); help iat exp chính xác
    public Clock clock() {
        return Clock.systemUTC();
    }
}