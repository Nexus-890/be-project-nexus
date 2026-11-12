package com.group5.shipmentservice.shared.config;

import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.dto.response.CarrierResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.CarrierService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ShippingConfiguration.class, CarrierRegistry.class,
                    CarrierExecutor.class, CarrierService.class);

    @Test
    void fakeIsDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FakeCarrierAdapter.class);
            assertThat(context.getBean(CarrierService.class).getCarriers()).isEmpty();
        });
    }

    @Test
    void noneReturnsNoCarrier() {
        runner.withPropertyValues("nexus.shipping.carrier=NONE").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(FakeCarrierAdapter.class);
            assertThat(context.getBean(CarrierService.class).getCarriers()).isEmpty();
        });
    }

    @Test
    void fakeRequiresExplicitConfiguration() {
        runner.withPropertyValues("nexus.shipping.carrier=FAKE").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(FakeCarrierAdapter.class);
            assertThat(context.getBean(CarrierService.class).getCarriers())
                    .containsExactly(new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, true));
        });
    }

    @Test
    void invalidCarrierConfigurationFailsStartup() {
        runner.withPropertyValues("nexus.shipping.carrier=GNH").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void previewPolicyCanBeConfiguredIndependentlyOfCarrierSelection() {
        runner.withPropertyValues("nexus.shipping.ghn.payment-type-id=2",
                        "nexus.shipping.ghn.required-note=CHOXEMHANGKHONGTHU")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GhnProperties properties = context.getBean(GhnProperties.class);
                    assertThat(properties.paymentTypeId()).isEqualTo(2);
                    assertThat(properties.requiredNote().name()).isEqualTo("CHOXEMHANGKHONGTHU");
                });
    }

    @Test
    void invalidPaymentTypeFailsStartup() {
        for (int paymentType : new int[]{0, 3}) {
            runner.withPropertyValues("nexus.shipping.ghn.payment-type-id=" + paymentType)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void invalidDeliveryNoteFailsStartup() {
        runner.withPropertyValues("nexus.shipping.ghn.required-note=GHI_CHU_KHONG_HOP_LE")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidGhnTimeoutOrNegativeCacheTtlFailsStartup() {
        for (String property : new String[]{"nexus.shipping.ghn.http-timeout-ms=0",
                "nexus.shipping.ghn.master-data-cache-ttl-ms=-1"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    void ghnCacheCanBeDisabledWithoutDisablingCarrierTimeout() {
        runner.withPropertyValues("nexus.shipping.ghn.master-data-cache-ttl-ms=0").run(context -> {
            assertThat(context).hasNotFailed();
            GhnProperties properties = context.getBean(GhnProperties.class);
            assertThat(properties.masterDataCacheTtlMs()).isZero();
            assertThat(properties.httpTimeoutMs()).isEqualTo(1500);
        });
    }
}
