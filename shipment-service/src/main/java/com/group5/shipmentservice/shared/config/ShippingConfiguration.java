package com.group5.shipmentservice.shared.config;

import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ShippingProperties.class, GhnProperties.class})
public class ShippingConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "nexus.shipping", name = "carrier", havingValue = "FAKE")
    public FakeCarrierAdapter fakeCarrierAdapter() {
        return new FakeCarrierAdapter();
    }

    @Bean
    @ConditionalOnProperty(prefix = "nexus.shipping", name = "carrier", havingValue = "GHN")
    public GhnAdapter ghnAdapter(GhnProperties ghn, ShippingProperties shipping, Environment environment,
                                  ObjectMapper mapper) {
        return new GhnAdapter(ghn, shipping, environment, mapper);
    }
}
