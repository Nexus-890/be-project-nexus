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
    private int expirationDays = 7;

    public int getMaxActivePerUser() {
        return maxActivePerUser;
    }

    public void setMaxActivePerUser(int maxActivePerUser) {
        this.maxActivePerUser = maxActivePerUser;
    }

    public boolean isAllowGuestCart() {
        return allowGuestCart;
    }

    public void setAllowGuestCart(boolean allowGuestCart) {
        this.allowGuestCart = allowGuestCart;
    }

    public int getExpirationDays() {
        return expirationDays;
    }

    public void setExpirationDays(int expirationDays) {
        this.expirationDays = expirationDays;
    }
}