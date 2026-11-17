package com.group5.configservice.systemconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/** Gửi yêu cầu refresh qua URL do deployment cấu hình; không nhận URL tuỳ ý từ người dùng. */
@Component
public class SystemConfigRefresher {
    private final RestClient client;
    private final Map<String, String> endpoints;

    /** Giới hạn thời gian gọi để một service lỗi không treo API admin. */
    public SystemConfigRefresher(
            @Value("${nexus.system-config.notification-url:http://localhost:8086}") String notification,
            @Value("${nexus.system-config.user-url:http://localhost:8081}") String user,
            @Value("${nexus.system-config.catalog-url:http://localhost:8082}") String catalog,
            @Value("${nexus.system-config.commerce-url:http://localhost:8083}") String commerce,
            @Value("${nexus.system-config.auction-url:http://localhost:8084}") String auction,
            @Value("${nexus.system-config.inventory-url:http://localhost:8085}") String inventory,
            @Value("${nexus.system-config.payment-url:http://localhost:8087}") String payment,
            @Value("${nexus.system-config.shipment-url:http://localhost:8088}") String shipment,
            @Value("${nexus.system-config.rating-url:http://localhost:8089}") String rating) {
        endpoints = Map.of("notification-service", notification, "user-service", user, "catalog-service", catalog,
                "commerce-service", commerce, "auction-service", auction, "inventory-service", inventory,
                "payment-service", payment, "shipment-service", shipment, "rating-service", rating);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }

    /** Chuyển tiếp JWT Admin hiện tại; token chỉ tồn tại trong request và không được ghi vào kho. */
    public void refresh(String service, String token) {
        if (service.equals("application")) {
            for (String owner : endpoints.keySet()) refresh(owner, token);
            return;
        }
        String baseUrl = endpoints.get(service);
        if (baseUrl == null) return;
        client.post().uri(baseUrl + "/actuator/refresh")
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve().toBodilessEntity();
    }
}
