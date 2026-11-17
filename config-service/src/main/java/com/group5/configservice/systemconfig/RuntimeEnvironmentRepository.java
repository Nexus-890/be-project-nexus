package com.group5.configservice.systemconfig;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.NativeEnvironmentProperties;
import org.springframework.cloud.config.server.environment.NativeEnvironmentRepository;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/** Giữ Config Server native làm nguồn gốc và đặt override theo đúng service lên đầu. */
public class RuntimeEnvironmentRepository extends NativeEnvironmentRepository {
    private final SystemConfigStore store;
    private volatile Map<String, Object> tentative = Map.of();

    /** Dùng cùng search-locations/profile/label của Config Server đang chạy. */
    public RuntimeEnvironmentRepository(ConfigurableEnvironment environment, NativeEnvironmentProperties properties,
                                        ObservationRegistry observations, SystemConfigStore store) {
        super(environment, properties, observations);
        this.store = store;
    }

    /** Cung cấp override cho Config Client khi khởi động hoặc actuator refresh. */
    @Override public Environment findOne(String application, String profile, String label) {
        return findOne(application, profile, label, false);
    }

    /** Ghép override đã commit và thay đổi tạm dành cho lần refresh đang được xác minh. */
    @Override public Environment findOne(String application, String profile, String label, boolean includeOrigin) {
        Environment result = super.findOne(application, profile, label, includeOrigin);
        Map<String, Object> values = store.values();
        values.putAll(tentative);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            SystemConfigKey key = SystemConfigKey.require(entry.getKey());
            if (key.service().equals(application) || key.service().equals("application")) {
                properties.put(key.property(), entry.getValue());
            }
            // Commerce và Shipment dùng cùng ngưỡng; không để checkout và quote tính lệch nhau.
            if (key.key().equals("FREE_SHIPPING_THRESHOLD") && application.equals("commerce-service")) {
                properties.put("nexus.checkout.free-shipping-threshold", entry.getValue());
            }
        }
        if (!properties.isEmpty()) result.addFirst(new PropertySource("system-config-runtime", properties));
        return result;
    }

    /** Đọc cấu hình gốc không có override để hiển thị giá trị hiện hành chính xác. */
    public Environment baseline(String service) { return super.findOne(service, "default", null, false); }

    /** Chỉ có một PUT tại một thời điểm; không ghi audit cho thay đổi chưa refresh thành công. */
    public void tentative(String key, Object value) { tentative = Map.of(key, value); }

    /** Kết thúc lần thay đổi để các lần GET tiếp theo dùng kho đã commit. */
    public void clearTentative() { tentative = Map.of(); }
}
