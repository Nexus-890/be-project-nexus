package com.group5.apigateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

//gọi service thông quan các service name trong eureka server thay vì gọi bằng IP/port
@Configuration
public class WebclientConfig {
    @Bean
    @LoadBalanced
    public WebClient.Builder webClientBuilder(){return WebClient.builder();}
}
