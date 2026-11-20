package com.group5.shipmentservice.shared.config;

import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ShippingProperties.class, GhnProperties.class})
public class ShippingConfiguration {
    @Bean
    @Conditional(FakeEnabled.class)
    @ConditionalOnMissingBean(FakeCarrierAdapter.class)
    public FakeCarrierAdapter fakeCarrierAdapter(Environment environment) {
        return new FakeCarrierAdapter(Clock.systemUTC(), environment.getProperty("FAKE_WEBHOOK_SECRET"));
    }

    @Bean
    @Conditional(GhnEnabled.class)
    @ConditionalOnMissingBean(GhnAdapter.class)
    public GhnAdapter ghnAdapter(GhnProperties ghn, ShippingProperties shipping, Environment environment,
                                  ObjectMapper mapper) {
        return new GhnAdapter(ghn, shipping, environment, mapper);
    }

    private static boolean enabled(Environment environment, String carrier) {
        String legacy = environment.getProperty("nexus.shipping.carrier", "NONE");
        for (String key : new String[]{"quote-carrier", "booking-carrier"}) {
            String configured = environment.getProperty("nexus.shipping." + key);
            if (carrier.equalsIgnoreCase(configured == null || configured.isBlank() ? legacy : configured)) return true;
        }
        return Binder.get(environment).bind("nexus.shipping.checkout-carriers", Bindable.listOf(String.class))
                .orElse(java.util.List.of()).stream().anyMatch(code -> carrier.equalsIgnoreCase(code.trim()));
    }

    static class FakeEnabled implements Condition {
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return enabled(context.getEnvironment(), "FAKE");
        }
    }

    static class GhnEnabled implements Condition {
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return enabled(context.getEnvironment(), "GHN");
        }
    }
}
