package com.group5.shipmentservice.shipping.client.config;

import com.group5.shipmentservice.shipping.client.CatalogFeignHttpClient;
import feign.Retryer;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

public class CatalogFeignConfiguration {
    @Bean(destroyMethod = "close")
    public CatalogLoadBalancerClient catalogFeignClient(LoadBalancerClient loadBalancer,
                                                       LoadBalancerClientFactory factory,
                                                       ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return new CatalogLoadBalancerClient(new CatalogFeignHttpClient(), loadBalancer, factory,
                transformers.orderedStream().toList());
    }

    @Bean
    public Retryer catalogRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Decoder catalogDecoder(ObjectMapper mapper) {
        return new CatalogResponseDecoder(mapper);
    }
}
