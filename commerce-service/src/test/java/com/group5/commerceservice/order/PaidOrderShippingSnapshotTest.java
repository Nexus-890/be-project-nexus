package com.group5.commerceservice.order;

import com.group5.common_core.exception.DomainException;
import com.group5.commerceservice.order.entity.Order;
import com.group5.commerceservice.order.repository.OrderRepository;
import com.group5.commerceservice.order.service.OrderShippingSnapshotService;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(PaidOrderShippingSnapshotTest.JpaConfig.class)
class PaidOrderShippingSnapshotTest {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final UUID WAREHOUSE = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final OffsetDateTime PAID_AT = OffsetDateTime.parse("2026-11-11T03:00:00Z");
    private static final String SHIPPING = """
        {"warehouseId":"44444444-4444-4444-8444-444444444444",
         "carrier":"FAKE", "serviceType":"STANDARD",
         "recipient":{"name":"Trusted Buyer","phone":"0900000000",
           "address":{"line1":"1 Order Street","wardCode":"W01","provinceCode":"P01"}},
         "items":[{"productName":"stale-name","quantity":999}],
         "parcel":{"weightGrams":1000,"lengthCm":20,"widthCm":15,"heightCm":10}}
        """;

    static { POSTGRES.start(); }

    @Autowired OrderShippingSnapshotService snapshots;
    @Autowired DataSource source;

    @Test
    void paidSnapshotUsesFrozenOrderMoneyAndActualOrderLinesWithoutMutatingStoredJson() {
        UUID orderId = order(OrderStatus.PAID, PAID_AT, SHIPPING);

        var snapshot = snapshots.get(orderId);

        assertThat(snapshot.orderId()).isEqualTo(orderId);
        assertThat(snapshot.buyerId()).isEqualTo(jdbc().queryForObject("SELECT user_id FROM orders WHERE id=?", UUID.class, orderId));
        assertThat(snapshot.orderVersion()).isEqualTo(4);
        assertThat(snapshot.status()).isEqualTo("PAID");
        assertThat(snapshot.paidAt()).isEqualTo(PAID_AT);
        assertThat(snapshot.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(snapshot.shippingFee()).isEqualByComparingTo("15000.00");
        assertThat(snapshot.shippingInfo()).containsEntry("carrier", "FAKE").containsEntry("pickupAddress", null);
        assertThat(snapshot.shippingInfo().get("items")).isEqualTo(java.util.List.of(Map.of(
                "skuId", jdbc().queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId),
                "productName", "Order Item Snapshot", "quantity", 2)));
        assertThat(jdbc().queryForObject("SELECT shipping_info->'items'->0->>'productName' FROM orders WHERE id=?", String.class, orderId))
                .isEqualTo("stale-name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyAddressNamesRemainNamesAndDoNotBecomeProviderCodes() {
        UUID orderId = order(OrderStatus.PAID, PAID_AT, """
            {"shippingAddress":{"recipientName":"Legacy Buyer","phoneNumber":"0900000000",
             "addressLine":"1 Legacy Street","ward":"Ward Name","district":"District Name",
             "city":"Ho Chi Minh","countryCode":"VN"}}
            """);

        var snapshot = snapshots.get(orderId);

        Map<String, Object> recipient = (Map<String, Object>) snapshot.shippingInfo().get("recipient");
        assertThat(recipient).containsEntry("name", "Legacy Buyer").containsEntry("phone", "0900000000");
        Map<String, Object> address = (Map<String, Object>) recipient.get("address");
        assertThat(address).containsEntry("line1", "1 Legacy Street").containsEntry("countryCode", "VN")
                .doesNotContainKeys("wardCode", "districtCode", "provinceCode");
        assertThat(snapshot.warehouseId()).isNull();
        assertThat(snapshot.shippingInfo()).containsEntry("parcel", null).containsEntry("pickupAddress", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacySnapshotCanPreserveExplicitCodesWithoutInferringThemFromNames() {
        UUID orderId = order(OrderStatus.PAID, PAID_AT, """
            {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0900000000",
             "addressLine":"1 Legacy Street","wardCode":"W01","provinceCode":"P01"}}
            """);

        Map<String, Object> recipient = (Map<String, Object>) snapshots.get(orderId).shippingInfo().get("recipient");

        assertThat((Map<String, Object>) recipient.get("address"))
                .containsEntry("wardCode", "W01").containsEntry("provinceCode", "P01");
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PAID", mode = EnumSource.Mode.EXCLUDE)
    void onlyPaidOrdersCanSupplyAManualShippingSnapshot(OrderStatus status) {
        UUID orderId = order(status, PAID_AT, SHIPPING);

        assertThatThrownBy(() -> snapshots.get(orderId)).isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(409, "COMMERCE_ORDER_NOT_PAID");
    }

    @Test
    void unknownOrderHasANamedNotFoundError() {
        assertThatThrownBy(() -> snapshots.get(UUID.randomUUID())).isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(404, "COMMERCE_ORDER_NOT_FOUND");
    }

    @Test
    void paidWithoutPersistedPaymentTimestampFailsClosed() {
        UUID orderId = order(OrderStatus.PAID, null, SHIPPING);

        assertThatThrownBy(() -> snapshots.get(orderId)).isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(409, "COMMERCE_INVALID_SHIPPING_SNAPSHOT");
    }

    @Test
    void invalidWarehouseReferenceFailsWithoutInventingAUuid() {
        UUID orderId = order(OrderStatus.PAID, PAID_AT, SHIPPING.replace(WAREHOUSE.toString(), "WH_001"));

        assertThatThrownBy(() -> snapshots.get(orderId)).isInstanceOf(DomainException.class)
                .extracting("code", "errorCode").containsExactly(409, "COMMERCE_INVALID_SHIPPING_SNAPSHOT");
    }

    @Test
    void incompleteRecipientAndMissingOrderItemsCannotSupplyASnapshot() {
        UUID missingAddress = order(OrderStatus.PAID, PAID_AT, "{}");
        UUID missingItems = order(OrderStatus.PAID, PAID_AT, SHIPPING);
        jdbc().update("DELETE FROM order_items WHERE order_id=?", missingItems);

        for (UUID orderId : java.util.List.of(missingAddress, missingItems)) {
            assertThatThrownBy(() -> snapshots.get(orderId)).isInstanceOf(DomainException.class)
                    .extracting("code", "errorCode").containsExactly(409, "COMMERCE_INVALID_SHIPPING_SNAPSHOT");
        }
    }

    private UUID order(OrderStatus status, OffsetDateTime paidAt, String shippingInfo) {
        UUID buyerId = UUID.randomUUID();
        UUID groupId = jdbc().queryForObject("""
            INSERT INTO order_groups (buyer_id,idempotency_key,total_amount,due_at)
            VALUES (?,?,215000.00,now()+interval '15 minutes') RETURNING id
            """, UUID.class, buyerId, UUID.randomUUID().toString());
        UUID orderId = jdbc().queryForObject("""
            INSERT INTO orders(order_group_id,user_id,seller_id,order_number,order_type,status,subtotal,total_amount,
              shipping_fee,shipping_info,idempotency_key,paid_at,version)
            VALUES(?,?,?,?,'STANDARD',?::order_status,200000.00,215000.00,15000.00,?::jsonb,?,?,4) RETURNING id
            """, UUID.class, groupId, buyerId, UUID.randomUUID(), "NX-SHIP-" + UUID.randomUUID().toString().substring(0, 16),
                status.name(), shippingInfo, UUID.randomUUID().toString(), paidAt);
        jdbc().update("""
            INSERT INTO order_items(order_id,product_id,sku_id,product_name,quantity,unit_price,total_price)
            VALUES(?,?,?,'Order Item Snapshot',2,100000.00,200000.00)
            """, orderId, UUID.randomUUID(), UUID.randomUUID());
        return orderId;
    }

    private JdbcTemplate jdbc() { return new JdbcTemplate(source); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import(OrderShippingSnapshotService.class)
    static class JpaConfig {
        @Bean
        DataSource dataSource() {
            DataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=commerce_shipping_snapshot",
                    POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure().dataSource(source).schemas("commerce_shipping_snapshot")
                    .defaultSchema("commerce_shipping_snapshot").load().migrate();
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
