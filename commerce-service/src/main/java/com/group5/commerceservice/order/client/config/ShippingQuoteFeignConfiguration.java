package com.group5.commerceservice.order.client.config;

import feign.Request;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

public class ShippingQuoteFeignConfiguration extends OrderFeignConfiguration {
    @Override
    @Bean(destroyMethod = "close")
    public OrderHttpClient orderHttpClient(LoadBalancerClient loadBalancer, LoadBalancerClientFactory factory,
                                           ObjectProvider<LoadBalancerFeignRequestTransformer> transformers,
                                           @Value("${nexus.order.shipping-timeout-ms:3500}") long timeoutMillis) {
        return new OrderHttpClient(loadBalancer, factory, transformers.orderedStream().toList(), timeoutMillis,
                OrderHttpClient.SHIPPING_MAX_TIMEOUT_MILLIS);
    }

    @Override
    @Bean
    public Request.Options orderRequestOptions(@Value("${nexus.order.shipping-timeout-ms:3500}") long timeoutMillis) {
        return new Request.Options(timeoutMillis, TimeUnit.MILLISECONDS, timeoutMillis, TimeUnit.MILLISECONDS, false);
    }
}
