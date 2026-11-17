package com.group5.configservice.systemconfig;

import com.group5.common_core.exception.DomainException;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Danh mục 57 cấu hình SRS 2.6; giá trị mặc định được tách khỏi giá trị đang chạy. */
public record SystemConfigKey(String key, String service, String property, Object defaultValue,
                              String description, Kind kind) {
    public enum Kind { BOOLEAN, INTEGER, DECIMAL, STRING }

    private static final List<SystemConfigKey> KEYS = List.of(
            text("SYSTEM_ENV", "application", "nexus.system.env", "DEV", "System environment (DEV / STAGING / PROD)"),
            text("SYSTEM_TIMEZONE", "application", "nexus.system.timezone", "UTC", "System default time zone"),
            number("SESSION_TIMEOUT_MINUTES", "user-service", "nexus.iam.session-timeout-minutes", 30, "User session timeout"),
            number("MAX_LOGIN_ATTEMPTS", "user-service", "nexus.iam.max-login-attempts", 5, "Maximum failed login attempts"),
            text("PASSWORD_POLICY", "user-service", "nexus.iam.password-policy", "STANDARD", "Password validation rules"),
            number("ACCESS_TOKEN_EXPIRY_MINUTES", "user-service", "nexus.iam.access-token-expiry-minutes", 60, "Access token expiration time"),
            flag("ALLOW_SELF_REGISTRATION", "user-service", "nexus.iam.allow-self-registration", true, "Allow users to self register"),
            text("DEFAULT_USER_ROLE", "user-service", "nexus.iam.default-user-role", "USER", "Default role assigned to new users"),
            flag("USER_SOFT_DELETE", "user-service", "nexus.iam.user-soft-delete", true, "Use soft delete for user accounts"),
            text("DEFAULT_ROLE_PRIVILEGES", "user-service", "nexus.iam.default-role-privileges", "READ_ONLY", "Default privileges for new roles"),
            text("PRODUCT_STATUSES", "catalog-service", "nexus.catalog.product-statuses", "DRAFT, ACTIVE, INACTIVE", "Supported product statuses"),
            number("MAX_PRODUCT_IMAGES", "catalog-service", "nexus.catalog.max-product-images", 5, "Maximum images per product"),
            flag("ALLOW_PRODUCT_DELETE_WITH_ORDER", "catalog-service", "nexus.catalog.allow-product-delete-with-order", false, "Allow deleting product with orders"),
            flag("PRODUCT_AUCTION_ENABLED", "catalog-service", "nexus.catalog.product-auction-enabled", true, "Allow products to be listed in auctions"),
            number("CATEGORY_MAX_LEVEL", "catalog-service", "nexus.catalog.category-max-level", 3, "Maximum category hierarchy depth"),
            flag("ALLOW_CATEGORY_DELETE_WITH_PRODUCT", "catalog-service", "nexus.catalog.allow-category-delete-with-product", false, "Allow deleting category with products"),
            text("CATEGORY_DEFAULT_SORT", "catalog-service", "nexus.catalog.category-tree.sort-type", "NAME_ASC", "Default category sort order"),
            number("MAX_ACTIVE_CART_PER_USER", "commerce-service", "nexus.cart.max-active-per-user", 1, "Maximum active cart per user"),
            number("CART_EXPIRATION_DAYS", "commerce-service", "nexus.cart.expiration-days", 7, "Cart expiration time"),
            flag("ALLOW_GUEST_CART", "commerce-service", "nexus.cart.allow-guest-cart", true, "Allow guest users to have carts"),
            flag("CART_MERGE_ON_LOGIN", "commerce-service", "nexus.cart.merge-on-login", true, "Merge guest cart on login"),
            flag("CHECKOUT_PRICE_REVALIDATION", "commerce-service", "nexus.checkout.price-revalidation", true, "Recalculate price during checkout"),
            number("CHECKOUT_TIMEOUT_MINUTES", "commerce-service", "nexus.checkout.timeout-minutes", 15, "Checkout timeout"),
            flag("ORDER_IDEMPOTENCY_ENABLED", "commerce-service", "nexus.order.idempotency-enabled", true, "Enable idempotent order creation"),
            text("PAYMENT_PROVIDER", "payment-service", "nexus.payment.provider", "STRIPE", "Payment gateway provider"),
            number("PAYMENT_RETRY_LIMIT", "payment-service", "nexus.payment.retry-limit", 3, "Maximum payment retry attempts"),
            number("PAYMENT_CALLBACK_TIMEOUT_MINUTES", "payment-service", "nexus.payment.callback-timeout-minutes", 10, "Payment callback timeout"),
            number("PAYMENT_IDEMPOTENCY_WINDOW_MINUTES", "payment-service", "nexus.payment.idempotency-window-minutes", 60, "Idempotency window for callbacks"),
            number("AUCTION_MIN_DURATION_MINUTES", "auction-service", "nexus.auction.min-duration-minutes", 60, "Minimum auction duration"),
            number("AUCTION_MAX_DURATION_HOURS", "auction-service", "nexus.auction.max-duration-hours", 168, "Maximum auction duration"),
            decimal("DEFAULT_BID_INCREMENT", "auction-service", "nexus.auction.default-bid-increment", "10", "Default bid increment value"),
            number("MAX_ACTIVE_AUCTIONS_PER_SELLER", "auction-service", "nexus.auction.max-active-per-seller", 5, "Max active auctions per seller"),
            flag("ANTI_SNIPING_ENABLED", "auction-service", "nexus.auction.anti-sniping-enabled", true, "Enable anti sniping"),
            number("ANTI_SNIPING_EXTENSION_MINUTES", "auction-service", "nexus.auction.anti-sniping-extension-minutes", 5, "Extend auction if bid placed near end"),
            number("AUCTION_PAYMENT_DEADLINE_HOURS", "auction-service", "nexus.auction.payment-deadline-hours", 24, "Payment deadline for auction winner"),
            text("DEFAULT_WAREHOUSE_ID", "inventory-service", "nexus.inventory.default-warehouse-code", "WH_001", "Default warehouse identifier"),
            number("INVENTORY_RESERVATION_TIMEOUT_MINUTES", "inventory-service", "nexus.inventory.reservation-timeout-minutes", 30, "Inventory reservation timeout"),
            flag("ALLOW_OVERSELL", "inventory-service", "nexus.inventory.allow-oversell", false, "Allow overselling inventory"),
            number("MAX_WAREHOUSES_PER_ORDER", "inventory-service", "nexus.inventory.max-warehouses-per-order", 2, "Max warehouses per order"),
            text("SUPPORTED_CARRIERS", "shipment-service", "nexus.shipping.supported-carriers", "GHN, GHTK", "Supported shipping carriers"),
            text("DEFAULT_CARRIER", "shipment-service", "nexus.shipping.carrier", "GHN", "Default shipping carrier"),
            number("SHIPPING_API_TIMEOUT_MS", "shipment-service", "nexus.shipping.api-timeout-ms", 3000, "Carrier API timeout"),
            number("SHIPPING_RETRY_LIMIT", "shipment-service", "nexus.shipping.retry-limit", 3, "Shipping request retry limit"),
            decimal("FREE_SHIPPING_THRESHOLD", "shipment-service", "nexus.shipping.free-shipping-threshold", "100", "Order amount for free shipping"),
            flag("EMAIL_NOTIFICATION_ENABLED", "notification-service", "nexus.notification.email-enabled", true, "Enable email notifications"),
            flag("PUSH_NOTIFICATION_ENABLED", "notification-service", "nexus.notification.push-enabled", true, "Enable push notifications"),
            flag("SMS_NOTIFICATION_ENABLED", "notification-service", "nexus.notification.sms-enabled", false, "Enable SMS notifications"),
            number("NOTIFICATION_RETRY_LIMIT", "notification-service", "nexus.notification.delivery.retry-limit", 3, "Notification retry attempts"),
            flag("TRANSACTIONAL_NOTIFICATION_MANDATORY", "notification-service", "nexus.notification.transactional-mandatory", true, "Transactional notifications mandatory"),
            number("MIN_REPUTATION_TO_BID", "rating-service", "nexus.rating.min-reputation-to-bid", 40, "Minimum reputation score to bid"),
            number("MIN_REPUTATION_TO_SELL", "rating-service", "nexus.rating.min-reputation-to-sell", 50, "Minimum reputation score to create auction"),
            text("TRUST_LEVEL_THRESHOLDS", "rating-service", "nexus.rating.trust-level-thresholds", "CONFIGURED", "Trust level score thresholds"),
            number("PAYMENT_TIMEOUT_PENALTY_SCORE", "rating-service", "nexus.rating.payment-timeout-penalty-score", 10, "Penalty score for payment timeout"),
            flag("FEATURE_AUCTION_ENABLED", "application", "nexus.features.auction-enabled", true, "Enable auction feature"),
            flag("FEATURE_MULTI_WAREHOUSE_ENABLED", "application", "nexus.features.multi-warehouse-enabled", false, "Enable multi warehouse support"),
            flag("FEATURE_SMS_NOTIFICATION_ENABLED", "application", "nexus.features.sms-notification-enabled", false, "Enable SMS notifications"),
            flag("FEATURE_REPUTATION_ENFORCEMENT", "application", "nexus.features.reputation-enforcement", true, "Enable reputation enforcement")
    );

    /** Trả danh mục bất biến theo đúng thứ tự trong SRS. */
    public static List<SystemConfigKey> all() {
        return KEYS;
    }

    /** Chỉ chấp nhận tên cấu hình đã đăng ký, không mở quyền ghi thuộc tính tùy ý. */
    public static SystemConfigKey require(String key) {
        return KEYS.stream().filter(entry -> entry.key.equals(key)).findFirst()
                .orElseThrow(() -> new DomainException(ConfigErrorCode.CONFIG_KEY_UNKNOWN));
    }

    /** Kiểm tra kiểu JSON và miền giá trị trước khi lưu nguồn cấu hình. */
    public Object validate(Object value) {
        try {
            return switch (kind) {
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) throw invalid();
                    yield value;
                }
                case INTEGER -> {
                    if (!(value instanceof Number numeric)) throw invalid();
                    int integer = new BigDecimal(numeric.toString()).intValueExact();
                    boolean score = key.equals("MIN_REPUTATION_TO_BID") || key.equals("MIN_REPUTATION_TO_SELL")
                            || key.equals("PAYMENT_TIMEOUT_PENALTY_SCORE");
                    boolean shippingRetry = key.equals("SHIPPING_RETRY_LIMIT");
                    if (integer < (score || shippingRetry ? 0 : 1) || (score && integer > 100)) throw invalid();
                    if (shippingRetry && integer > 3) throw invalid();
                    if (key.equals("SHIPPING_API_TIMEOUT_MS") && integer < 100) throw invalid();
                    if ((key.equals("CATEGORY_MAX_LEVEL") || key.equals("MAX_PRODUCT_IMAGES"))
                            && integer > Short.MAX_VALUE) throw invalid();
                    yield integer;
                }
                case DECIMAL -> {
                    if (!(value instanceof Number numeric)) throw invalid();
                    BigDecimal decimal = new BigDecimal(numeric.toString());
                    if (decimal.signum() < 0 || (key.equals("DEFAULT_BID_INCREMENT") && decimal.signum() == 0))
                        throw invalid();
                    if (key.equals("DEFAULT_BID_INCREMENT")) {
                        BigDecimal normalized = decimal.stripTrailingZeros();
                        if (normalized.scale() > 2 || normalized.precision() - normalized.scale() > 17) throw invalid();
                    }
                    yield decimal;
                }
                case STRING -> {
                    if (!(value instanceof String string) || string.isBlank() || string.length() > 2048) throw invalid();
                    validateText(string);
                    yield string;
                }
            };
        } catch (ArithmeticException | IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    /** Kiểm tra giá trị định danh đã biết và múi giờ mà không tự đổi kiểu dữ liệu. */
    private void validateText(String value) {
        if (key.equals("SYSTEM_ENV") && !Set.of("DEV", "STAGING", "PROD").contains(value)) throw invalid();
        if (key.equals("SYSTEM_TIMEZONE")) ZoneId.of(value);
        if (key.equals("CATEGORY_DEFAULT_SORT")
                && !Set.of("NAME_ASC", "NAME_DESC", "SLUG_ASC", "SLUG_DESC").contains(value)) throw invalid();
        if (key.equals("DEFAULT_CARRIER") && !Set.of("NONE", "FAKE", "GHN").contains(value)) throw invalid();
        if (key.equals("DEFAULT_USER_ROLE")
                && !Set.of("USER", "ADMIN", "SUPPORT_STAFF", "BUYER", "SELLER").contains(value)) throw invalid();
    }

    /** Tạo lỗi nghiệp vụ thống nhất cho mọi loại giá trị không hợp lệ. */
    private static DomainException invalid() {
        return new DomainException(ConfigErrorCode.CONFIG_VALUE_INVALID);
    }

    /** Khai báo một cấu hình boolean trong danh mục. */
    private static SystemConfigKey flag(String key, String service, String property, boolean value, String description) {
        return new SystemConfigKey(key, service, property, value, description, Kind.BOOLEAN);
    }

    /** Khai báo một cấu hình số nguyên trong danh mục. */
    private static SystemConfigKey number(String key, String service, String property, int value, String description) {
        return new SystemConfigKey(key, service, property, value, description, Kind.INTEGER);
    }

    /** Khai báo một cấu hình tiền hoặc bước giá, tránh làm tròn qua số thực. */
    private static SystemConfigKey decimal(String key, String service, String property, String value, String description) {
        return new SystemConfigKey(key, service, property, new BigDecimal(value), description, Kind.DECIMAL);
    }

    /** Khai báo một cấu hình dạng chuỗi trong danh mục. */
    private static SystemConfigKey text(String key, String service, String property, String value, String description) {
        return new SystemConfigKey(key, service, property, value, description, Kind.STRING);
    }
}
