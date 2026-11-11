package com.group5.shipmentservice.shipping.client.config;

import com.group5.shipmentservice.shipping.client.CommerceFeignHttpClient;
import feign.Retryer;
import feign.codec.Decoder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.cloud.openfeign.loadbalancer.LoadBalancerFeignRequestTransformer;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** Feign child-context configuration; intentionally not a component-scanned configuration class. */
public class CommerceFeignConfiguration {
    @Bean(destroyMethod = "close")
    public CommerceLoadBalancerClient commerceFeignClient(LoadBalancerClient loadBalancer,
            LoadBalancerClientFactory factory, ObjectProvider<LoadBalancerFeignRequestTransformer> transformers) {
        return new CommerceLoadBalancerClient(new CommerceFeignHttpClient(), loadBalancer, factory,
                transformers.orderedStream().toList());
    }

    @Bean
    public Retryer commerceRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Decoder commerceDecoder(ObjectMapper mapper) {
        return new CommerceResponseDecoder(mapper);
    }
}
