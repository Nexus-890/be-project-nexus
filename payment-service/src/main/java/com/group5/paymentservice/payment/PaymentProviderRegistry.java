package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.provider.PaymentProvider;
import com.group5.paymentservice.shared.config.PaymentProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class PaymentProviderRegistry {
    private final PaymentProvider provider;

    public PaymentProviderRegistry(PaymentProperties properties, List<PaymentProvider> providers) {
        Map<String, PaymentProvider> available = new LinkedHashMap<>();
        for (PaymentProvider candidate : providers) {
            String name = normalize(candidate.providerName());
            if (name.isEmpty()) {
                throw new IllegalStateException("PaymentProvider must declare a non-blank providerName");
            }
            if (available.putIfAbsent(name, candidate) != null) {
                throw new IllegalStateException("Duplicate PaymentProvider implementation for " + name);
            }
        }
        String configured = normalize(properties.provider());
        provider = available.get(configured);
        if (provider == null) {
            throw new IllegalStateException(
                    "No PaymentProvider implementation for nexus.payment.provider="
                            + (configured.isEmpty() ? "<missing or blank>" : properties.provider())
                            + "; available providers: " + available.keySet());
        }
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
}