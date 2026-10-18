package com.group5.commerceservice.shared.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nexus.cart")
public class CartProperties {

    private int maxActivePerUser = 1;

    private boolean allowGuestCart = false;
}