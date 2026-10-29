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

    // Số lượng cart ACTIVE tối đa cho mỗi user, giá trị mặc định là 1 theo SRS 2.6.
    // DB partial unique index (idx_carts_one_active) enforce giới hạn này.
    private int maxActivePerUser = 1;

    //Không cho phép cart.
    private boolean allowGuestCart = false;

    //Số ngày không hoạt động trước khi cart ACTIVE bị chuyển sang EXPIRED. Dùng giá trị >= 1.
    private int expirationDays = 7;

}