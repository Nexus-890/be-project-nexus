package com.group5.auctionservice.shared.config;

import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuctionPropertiesConfiguration {
    @Bean
    @RefreshScope
    AuctionProperties auctionProperties() {
        return new AuctionProperties();
    }
}
