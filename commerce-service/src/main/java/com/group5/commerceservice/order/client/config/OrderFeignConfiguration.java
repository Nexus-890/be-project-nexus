package com.group5.commerceservice.order.client.config;

import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

/** Cấu hình chỉ gắn vào hai client checkout, không thay đổi các Feign client khác. */
public class OrderFeignConfiguration {
    @Bean(destroyMethod = "close")
    public OrderHttpClient orderHttpClient(LoadBalancerClient loadBalancer, LoadBalancerClientFactory factory,
                                           ObjectProvider<LoadBalancerFeignRequestTransformer> transformers,
                                           @Value("${nexus.order.remote-timeout-ms:1800}") long timeoutMillis) {
        return new OrderHttpClient(loadBalancer, factory, transformers.orderedStream().toList(), timeoutMillis);
    }

    @Bean
    public Retryer orderRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Decoder orderResponseDecoder(ObjectMapper mapper) {
        return new OrderResponseDecoder(mapper);
    }

    @Bean
    public Request.Options orderRequestOptions(@Value("${nexus.order.remote-timeout-ms:1800}") long timeoutMillis) {
        return new Request.Options(timeoutMillis, TimeUnit.MILLISECONDS, timeoutMillis, TimeUnit.MILLISECONDS, false);
    }

    @Bean
    public RequestInterceptor orderBearerRelay() {
        return template -> {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
                // Đọc trên request thread trước khi transport dùng virtual thread, không lưu token trong singleton.
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.getToken().getTokenValue());
            }
        };
    }
}
