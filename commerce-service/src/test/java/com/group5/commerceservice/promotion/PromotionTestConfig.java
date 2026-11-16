package com.group5.commerceservice.promotion;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@TestConfiguration(proxyBeanMethods = false)
@Import({VoucherCheckoutService.class, VoucherLimitRepository.class, VoucherRepository.class, MegaSaleEventRepository.class,
        VoucherLifecycleService.class})
public class PromotionTestConfig {
    @Bean
    JdbcClient promotionJdbc(DataSource source) {
        return JdbcClient.create(source);
    }

    @Bean
    VoucherUsageRepository voucherUsages(JdbcClient jdbc) {
        return new VoucherUsageRepository(jdbc, new ObjectMapper());
    }
}
