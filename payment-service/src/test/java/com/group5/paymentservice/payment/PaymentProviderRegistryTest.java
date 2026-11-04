package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.provider.PaymentProvider;
import com.group5.paymentservice.payment.provider.PaymentProviderRegistry;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationResult;
import com.group5.paymentservice.payment.provider.sepay.SepayPaymentProvider;
import com.group5.paymentservice.shared.config.PaymentProperties;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRegistryTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                context.getEnvironment().getPropertySources().remove("systemProperties");
                context.getEnvironment().getPropertySources().remove("systemEnvironment");
            })
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(PaymentProviderRegistry.class);

    @Test
    void selectsSepayAmongOtherProviders() {
        var sepay = sepay();
        runner.withBean("sepay", PaymentProvider.class, () -> sepay)
                .withBean("fake", PaymentProvider.class, () -> new FakeProvider("FAKE"))
                .withPropertyValues("nexus.payment.provider=SEPAY")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentProperties.class).provider()).isEqualTo("SEPAY");
                    assertThat(context.getBean(PaymentProviderRegistry.class).getProvider()).isSameAs(sepay);
                });
    }

    @Test
    void supportsAnotherProviderWithoutChangingRegistry() {
        var fake = new FakeProvider("FUTURE");
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay)
                .withBean("future", PaymentProvider.class, () -> fake)
                .withPropertyValues("nexus.payment.provider=FUTURE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentProviderRegistry.class).getProvider()).isSameAs(fake);
                });
    }

    @Test
    void unknownProviderFailsContextStartupWithSettingAndValue() {
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay)
                .withPropertyValues("nexus.payment.provider=UNKNOWN")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "No PaymentProvider implementation for nexus.payment.provider=UNKNOWN"
                                    + "; available providers: [SEPAY]");
                });
    }

    @Test
    void missingSettingFailsInsteadOfSilentlyChoosingAProvider() {
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                    "nexus.payment.provider must not be blank");
        });
    }

    @Test
    void blankSettingFailsStartup() {
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay)
                .withPropertyValues("nexus.payment.provider= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("nexus.payment.provider must not be blank");
                });
    }

    @Test
    void noImplementationFailsStartup() {
        runner.withPropertyValues("nexus.payment.provider=SEPAY").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("available providers: []");
        });
    }

    @Test
    void duplicateNamesAreRejectedRatherThanSelectingArbitrarily() {
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay)
                .withBean("duplicate", PaymentProvider.class, () -> new FakeProvider("sepay"))
                .withPropertyValues("nexus.payment.provider=SEPAY")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "Duplicate PaymentProvider implementation for SEPAY");
                });
    }

    @Test
    void matchesNamesIgnoringCaseAndSurroundingWhitespace() {
        runner.withBean(SepayPaymentProvider.class, PaymentProviderRegistryTest::sepay)
                .withPropertyValues("nexus.payment.provider= sepay ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentProviderRegistry.class).getProvider().providerName())
                            .isEqualTo("SEPAY");
                });
    }

    @Test
    void initiationRejectsMissingStoredPaymentData() {
        assertThatThrownBy(() -> sepay().initiate(null))
                .isInstanceOf(com.group5.common_core.exception.DomainException.class)
                .hasMessageContaining("Stored payment data");
    }

    private static SepayPaymentProvider sepay() {
        return new SepayPaymentProvider(new SepayProperties(
                "970415", "0000000002", "NEXUS TEST", "compact", "SEVQR"));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    static class PropertiesConfiguration {
    }

    private record FakeProvider(String providerName) implements PaymentProvider {
        @Override
        public PaymentInitiationResult initiate(PaymentInitiationRequest request) {
            throw new UnsupportedOperationException("Selection-only test provider");
        }
    }
}