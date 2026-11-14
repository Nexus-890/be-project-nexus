package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemConfigKeyTest {
    /** Danh mục giữ đủ 57 khóa riêng biệt và thông tin sở hữu để định tuyến cấu hình. */
    @Test
    void catalogueContainsAllSrsKeysExactlyOnce() {
        List<SystemConfigKey> keys = SystemConfigKey.all();
        assertThat(keys).hasSize(57);
        assertThat(keys).extracting(SystemConfigKey::key).doesNotHaveDuplicates();
        assertThat(keys).allSatisfy(key -> {
            assertThat(key.service()).isNotBlank();
            assertThat(key.property()).startsWith("nexus.");
            assertThat(key.description()).isNotBlank();
            assertThat(key.validate(key.defaultValue())).isEqualTo(key.defaultValue());
        });
    }

    /** Mặc định lịch sử của SRS không bị ghi đè bằng giá trị đang chạy của từng service. */
    @Test
    void historicalDefaultsRemainSeparateFromEffectiveConfiguration() {
        assertThat(SystemConfigKey.require("DEFAULT_USER_ROLE").defaultValue()).isEqualTo("USER");
        assertThat(SystemConfigKey.require("PAYMENT_PROVIDER").defaultValue()).isEqualTo("STRIPE");
        assertThat(SystemConfigKey.require("EMAIL_NOTIFICATION_ENABLED").defaultValue()).isEqualTo(true);
        assertThat(SystemConfigKey.require("FREE_SHIPPING_THRESHOLD").defaultValue()).isEqualTo(new BigDecimal("100"));
        assertThat(SystemConfigKey.require("NOTIFICATION_RETRY_LIMIT").property())
                .isEqualTo("nexus.notification.delivery.retry-limit");
        assertThat(SystemConfigKey.require("PAYMENT_PROVIDER").service()).isEqualTo("payment-service");
        assertThat(SystemConfigKey.require("DEFAULT_WAREHOUSE_ID").service()).isEqualTo("inventory-service");
        assertThat(SystemConfigKey.require("DEFAULT_CARRIER").service()).isEqualTo("shipment-service");
        assertThat(SystemConfigKey.require("MIN_REPUTATION_TO_BID").service()).isEqualTo("rating-service");
    }

    /** Chặn khóa lạ và tên gần giống để API không trở thành cổng ghi thuộc tính tùy ý. */
    @Test
    void rejectsUnknownKeysInsteadOfNormalizingThem() {
        for (String key : List.of("PASSWORD", "notification_retry_limit", " NOTIFICATION_RETRY_LIMIT")) {
            assertThatThrownBy(() -> SystemConfigKey.require(key)).isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).getErrorCode())
                    .isEqualTo(ConfigErrorCode.CONFIG_KEY_UNKNOWN.code());
        }
    }

    /** Boolean phải là boolean JSON; chuỗi, số và null đều bị từ chối. */
    @Test
    void rejectsBooleanCoercion() {
        SystemConfigKey key = SystemConfigKey.require("USER_SOFT_DELETE");
        assertThat(key.validate(false)).isEqualTo(false);
        for (Object value : List.of("false", "true", 0, 1, List.of(false), Map.of("value", true))) reject(key, value);
        reject(key, null);
    }

    /** Giới hạn retry chỉ nhận số nguyên dương; các điểm danh tiếng cho phép số không. */
    @Test
    void enforcesIntegerRangesAndExactValues() {
        SystemConfigKey retry = SystemConfigKey.require("NOTIFICATION_RETRY_LIMIT");
        assertThat(retry.validate(7L)).isEqualTo(7);
        for (Object value : List.of(0, -1, "3", true, 1.5, Long.MAX_VALUE)) reject(retry, value);
        reject(retry, null);
        SystemConfigKey score = SystemConfigKey.require("MIN_REPUTATION_TO_BID");
        assertThat(score.validate(0)).isEqualTo(0);
        assertThat(score.validate(100)).isEqualTo(100);
        reject(score, -1);
        reject(score, 101);
    }

    /** Số tiền giữ BigDecimal và từ chối chuỗi hay số âm; bước giá phải lớn hơn không. */
    @Test
    void validatesDecimalsWithoutStringCoercion() {
        SystemConfigKey threshold = SystemConfigKey.require("FREE_SHIPPING_THRESHOLD");
        assertThat(threshold.validate(new BigDecimal("1000000.25"))).isEqualTo(new BigDecimal("1000000.25"));
        assertThat(threshold.validate(0)).isEqualTo(BigDecimal.ZERO);
        for (Object value : List.of("100", -1, true, Double.NaN, Double.POSITIVE_INFINITY)) reject(threshold, value);
        reject(SystemConfigKey.require("DEFAULT_BID_INCREMENT"), 0);
    }

    /** Chuỗi định danh và múi giờ phải hợp lệ, không tự đổi dữ liệu kiểu khác thành chuỗi. */
    @Test
    void validatesStringsEnvironmentTimezoneAndFixedRoles() {
        assertThat(SystemConfigKey.require("SYSTEM_ENV").validate("PROD")).isEqualTo("PROD");
        assertThat(SystemConfigKey.require("SYSTEM_TIMEZONE").validate("Asia/Ho_Chi_Minh"))
                .isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(SystemConfigKey.require("DEFAULT_USER_ROLE").validate("BUYER")).isEqualTo("BUYER");
        reject(SystemConfigKey.require("SYSTEM_ENV"), "LOCAL");
        reject(SystemConfigKey.require("SYSTEM_TIMEZONE"), "Unknown/Zone");
        reject(SystemConfigKey.require("DEFAULT_USER_ROLE"), "SUPERUSER");
        for (Object value : List.of("", " ", 3, true, Map.of())) reject(SystemConfigKey.require("PASSWORD_POLICY"), value);
    }

    /** Registry tuân theo range/enum của consumer thay vì nhận giá trị làm service lỗi khi bind. */
    @Test
    void respectsExistingServiceBindingConstraints() {
        assertThat(SystemConfigKey.require("SHIPPING_RETRY_LIMIT").validate(0)).isEqualTo(0);
        reject(SystemConfigKey.require("SHIPPING_RETRY_LIMIT"), 4);
        reject(SystemConfigKey.require("SHIPPING_API_TIMEOUT_MS"), 99);
        reject(SystemConfigKey.require("CATEGORY_MAX_LEVEL"), 32768);
        assertThat(SystemConfigKey.require("MAX_PRODUCT_IMAGES").validate(32767)).isEqualTo(32767);
        reject(SystemConfigKey.require("MAX_PRODUCT_IMAGES"), 32768);
        reject(SystemConfigKey.require("CATEGORY_DEFAULT_SORT"), "UNKNOWN");
        reject(SystemConfigKey.require("DEFAULT_CARRIER"), "GHTK");
        reject(SystemConfigKey.require("DEFAULT_BID_INCREMENT"), new BigDecimal("10.001"));
        reject(SystemConfigKey.require("DEFAULT_BID_INCREMENT"), new BigDecimal("100000000000000000"));
    }

    /** Kiểm tra mọi dữ liệu sai đều trả mã lỗi nghiệp vụ thống nhất. */
    private static void reject(SystemConfigKey key, Object value) {
        assertThatThrownBy(() -> key.validate(value)).isInstanceOf(DomainException.class)
                .extracting(error -> ((DomainException) error).getErrorCode())
                .isEqualTo(ConfigErrorCode.CONFIG_VALUE_INVALID.code());
    }
}
