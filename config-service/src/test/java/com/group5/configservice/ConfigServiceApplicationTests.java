package com.group5.configservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.group5.configservice.systemconfig.SystemConfigStore;
import com.group5.configservice.systemconfig.RuntimeEnvironmentRepository;
import com.group5.common_events.EventEnvelope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServiceApplicationTests {
    private static final Path STATE = Path.of(System.getProperty("java.io.tmpdir"),
            "config-server-context-" + UUID.randomUUID(), "state.json");
    @Autowired EnvironmentRepository environments;
    @Autowired SystemConfigStore store;
    @Autowired RuntimeEnvironmentRepository runtime;
    @LocalServerPort int port;

    /** Context test dùng file riêng và tắt relay, không sửa state đang chạy của developer. */
    @DynamicPropertySource
    static void isolatedState(DynamicPropertyRegistry properties) {
        properties.add("nexus.system-config.state-file", STATE::toString);
        properties.add("nexus.system-config.audit.enabled", () -> "false");
    }

	/** Khởi động đủ Config Server/JWT và xác minh composite thực sự trả override đúng owner. */
	@Test
	void contextLoads() throws Exception {
		var event = new EventEnvelope<>(UUID.randomUUID(), "SystemConfigUpdated", "NOTIFICATION_RETRY_LIMIT",
		        Instant.parse("2026-11-14T07:00:00Z"), "trace-config", Map.of("oldValue", 3, "newValue", 4));
		store.commit("NOTIFICATION_RETRY_LIMIT", 4, event);
		var notification = environments.findOne("notification-service", "default", null);
		assertThat(notification.getPropertySources().getFirst().getSource()
		        .get("nexus.notification.delivery.retry-limit")).isEqualTo(4);
		var user = environments.findOne("user-service", "default", null);
		assertThat(user.getPropertySources()).allSatisfy(source ->
		        assertThat(source.getSource().containsKey("nexus.notification.delivery.retry-limit")).isFalse());

		// Config Client đọc HTTP thật không cần bearer và vẫn nhận giao thức native, không wrap ApiResponse.
		var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
		        "http://127.0.0.1:" + port + "/notification-service/default")).GET().build(),
		        HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		var json = JsonMapper.shared().readTree(response.body());
		assertThat(json.path("propertySources").get(0).path("source")
		        .path("nexus.notification.delivery.retry-limit").asInt()).isEqualTo(4);
		assertThat(json.has("data")).isFalse();

		// Refresh đang xác minh thấy giá trị tạm; hủy tạm trả đúng source đã commit.
		runtime.tentative("NOTIFICATION_RETRY_LIMIT", 7);
		assertThat(environments.findOne("notification-service", "default", null).getPropertySources()
		        .getFirst().getSource().get("nexus.notification.delivery.retry-limit")).isEqualTo(7);
		runtime.clearTentative();
		assertThat(environments.findOne("notification-service", "default", null).getPropertySources()
		        .getFirst().getSource().get("nexus.notification.delivery.retry-limit")).isEqualTo(4);

		// Key chung phục vụ mọi owner; ngưỡng miễn ship dùng đúng hai property khác nhau.
		store.commit("FEATURE_AUCTION_ENABLED", false, event);
		store.commit("FREE_SHIPPING_THRESHOLD", new BigDecimal("500000"), event);
		var commerce = environments.findOne("commerce-service", "default", null).getPropertySources().getFirst().getSource();
		var shipping = environments.findOne("shipment-service", "default", null).getPropertySources().getFirst().getSource();
		assertThat(commerce.get("nexus.features.auction-enabled")).isEqualTo(false);
		assertThat(shipping.get("nexus.features.auction-enabled")).isEqualTo(false);
		assertThat(commerce.get("nexus.checkout.free-shipping-threshold")).isEqualTo(new BigDecimal("500000"));
		assertThat(shipping.get("nexus.shipping.free-shipping-threshold")).isEqualTo(new BigDecimal("500000"));
	}

}
