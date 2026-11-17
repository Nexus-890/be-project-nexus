package com.group5.shipmentservice.shared.config;

import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.FakeCarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.ghn.GhnAdapter;
import com.group5.shipmentservice.shipping.dto.response.CarrierResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.service.CarrierService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
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
            ShippingProperties properties = context.getBean(ShippingProperties.class);
            assertThat(properties.effectiveQuoteCarrier()).isEqualTo(ShippingProperties.CarrierMode.FAKE);
            assertThat(properties.effectiveBookingCarrier()).isEqualTo(ShippingProperties.CarrierMode.FAKE);
        });
    }

    @Test
    void ghnQuotesAndFakeBookingsRegisterBothAdaptersButOnlyAdvertiseGhnForCheckout() {
        runner.withPropertyValues("nexus.shipping.quote-carrier=GHN", "nexus.shipping.booking-carrier=FAKE",
                        "GHN_TOKEN=config-test-token", "nexus.shipping.ghn.shop-id=123")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GhnAdapter.class).hasSingleBean(FakeCarrierAdapter.class);
                    CarrierRegistry registry = context.getBean(CarrierRegistry.class);
                    assertThat(registry.getAdapters()).extracting(adapter -> adapter.code()).containsExactly("FAKE", "GHN");
                    assertThat(registry.getDefaultAdapter()).isSameAs(context.getBean(GhnAdapter.class));
                    assertThat(context.getBean(CarrierService.class).getCarriers())
                            .containsExactly(new CarrierResponse("GHN", "Giao Hàng Nhanh", true, true));
                });
    }

    @Test
    void bookingOnlyCarrierDoesNotBecomeTheQuoteDefault() {
        runner.withPropertyValues("nexus.shipping.quote-carrier=NONE", "nexus.shipping.booking-carrier=FAKE")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FakeCarrierAdapter.class).doesNotHaveBean(GhnAdapter.class);
                    assertThat(context.getBean(CarrierRegistry.class).getQuoteAdapters()).isEmpty();
                    assertThat(context.getBean(CarrierService.class).getCarriers()).isEmpty();
                });
    }

    @Test
    void legacyGhnSelectionStillEnablesBothQuotesAndBookings() {
        runner.withPropertyValues("nexus.shipping.carrier=GHN", "GHN_TOKEN=config-test-token",
                        "nexus.shipping.ghn.shop-id=123")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GhnAdapter.class).doesNotHaveBean(FakeCarrierAdapter.class);
                    ShippingProperties properties = context.getBean(ShippingProperties.class);
                    assertThat(properties.effectiveQuoteCarrier()).isEqualTo(ShippingProperties.CarrierMode.GHN);
                    assertThat(properties.effectiveBookingCarrier()).isEqualTo(ShippingProperties.CarrierMode.GHN);
                    assertThat(context.getBean(CarrierRegistry.class).getDefaultAdapter()).isSameAs(context.getBean(GhnAdapter.class));
                });
    }

    @Test
    void separateSettingsOverrideLegacyCarrierWithoutRequiringUnusedGhnCredentials() {
        runner.withPropertyValues("nexus.shipping.carrier=GHN", "nexus.shipping.quote-carrier=FAKE",
                        "nexus.shipping.booking-carrier=NONE")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FakeCarrierAdapter.class).doesNotHaveBean(GhnAdapter.class);
                    ShippingProperties properties = context.getBean(ShippingProperties.class);
                    assertThat(properties.effectiveQuoteCarrier()).isEqualTo(ShippingProperties.CarrierMode.FAKE);
                    assertThat(properties.effectiveBookingCarrier()).isEqualTo(ShippingProperties.CarrierMode.NONE);
                    assertThat(context.getBean(CarrierService.class).getCarriers())
                            .containsExactly(new CarrierResponse("FAKE", "Nhà vận chuyển giả lập", true, true));
                });
    }

    @Test
    void blankOverridesRetainLegacySelection() {
        runner.withPropertyValues("nexus.shipping.carrier=FAKE", "nexus.shipping.quote-carrier=",
                        "nexus.shipping.booking-carrier=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FakeCarrierAdapter.class);
                    ShippingProperties properties = context.getBean(ShippingProperties.class);
                    assertThat(properties.effectiveQuoteCarrier()).isEqualTo(ShippingProperties.CarrierMode.FAKE);
                    assertThat(properties.effectiveBookingCarrier()).isEqualTo(ShippingProperties.CarrierMode.FAKE);
                });
    }

    @Test
    void invalidCarrierConfigurationFailsStartup() {
        for (String property : new String[]{"carrier", "quote-carrier", "booking-carrier"}) {
            runner.withPropertyValues("nexus.shipping." + property + "=GNH").run(context ->
                    assertThat(context).hasFailed());
        }
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
