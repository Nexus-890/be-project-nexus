package com.group5.auctionservice.shared.config;

import com.group5.auctionservice.auction.AuctionRepository;
import com.group5.auctionservice.bidding.AntiSnipingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Kiểm tra binding/validation thật, không cần database, HTTP server hoặc Docker. */
class AuctionPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class, RefreshAutoConfiguration.class))
            .withUserConfiguration(AuctionPropertiesConfiguration.class);

    @Test
    void missingSettingsKeepSrsDefaultsAndAFiniteExtensionCap() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("auctionProperties");
            AuctionProperties properties = context.getBean(AuctionProperties.class);
            assertThat(AopUtils.isAopProxy(properties)).isTrue();
            assertThat(properties.isAntiSnipingEnabled()).isTrue();
            assertThat(properties.isReputationEnforcementEnabled()).isFalse();
            assertThat(properties.getAntiSnipingExtensionMinutes()).isEqualTo(5);
            assertThat(properties.getAntiSnipingMaxExtensionMinutes()).isEqualTo(30);
            assertThat(properties.getMinDurationMinutes()).isEqualTo(60);
            assertThat(properties.getMaxDurationHours()).isEqualTo(168);
            assertThat(properties.getMaxActivePerSeller()).isEqualTo(5);
            assertThat(properties.getDefaultBidIncrement()).isEqualByComparingTo("10");
        });
    }

    @Test
    void explicitAuctionAndAntiSnipingSettingsBindThroughTheProductionBean() {
        runner.withPropertyValues(
                "nexus.auction.anti-sniping-enabled=false",
                "nexus.auction.reputation-enforcement-enabled=true",
                "nexus.auction.anti-sniping-extension-minutes=2",
                "nexus.auction.anti-sniping-max-extension-minutes=7",
                "nexus.auction.min-duration-minutes=120",
                "nexus.auction.max-duration-hours=24",
                "nexus.auction.max-active-per-seller=2",
                "nexus.auction.default-bid-increment=27.50")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AuctionProperties properties = context.getBean(AuctionProperties.class);
                    assertThat(properties.isAntiSnipingEnabled()).isFalse();
                    assertThat(properties.isReputationEnforcementEnabled()).isTrue();
                    assertThat(properties.getAntiSnipingExtensionMinutes()).isEqualTo(2);
                    assertThat(properties.getAntiSnipingMaxExtensionMinutes()).isEqualTo(7);
                    assertThat(properties.getMinDurationMinutes()).isEqualTo(120);
                    assertThat(properties.getMaxDurationHours()).isEqualTo(24);
                    assertThat(properties.getMaxActivePerSeller()).isEqualTo(2);
                    assertThat(properties.getDefaultBidIncrement()).isEqualByComparingTo("27.50");
                });
    }

    @ParameterizedTest
    @CsvSource({
            "anti-sniping-extension-minutes, 0, antiSnipingExtensionMinutes",
            "anti-sniping-extension-minutes, -1, antiSnipingExtensionMinutes",
            "anti-sniping-max-extension-minutes, 0, antiSnipingMaxExtensionMinutes",
            "anti-sniping-max-extension-minutes, -1, antiSnipingMaxExtensionMinutes"
    })
    void nonPositiveExtensionSettingsFailBinding(String key, int value, String invalidField) {
        runner.withPropertyValues("nexus.auction." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class)
                    .rootCause().hasMessageContaining(invalidField);
        });
    }

    @Test
    void minimumDurationCannotExceedTheMaximumDuration() {
        runner.withPropertyValues("nexus.auction.min-duration-minutes=1441",
                        "nexus.auction.max-duration-hours=24")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class)
                            .rootCause().hasMessageContaining("min-duration-minutes exceeds the maximum duration");
                });
    }

    @Test
    void refreshRebindsSettingsUsedByAnExistingPolicyWithoutRecreatingTheConsumer() {
        Map<String, Object> source = new HashMap<>();
        runner.withUserConfiguration(PolicyConsumerConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("changed-config-server-values", source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    long startup = context.getStartupDate();
                    AntiSnipingPolicy policy = context.getBean(AntiSnipingPolicy.class);
                    AuctionProperties proxy = context.getBean(AuctionProperties.class);
                    AuctionRepository auctions = context.getBean(AuctionRepository.class);
                    UUID auction = UUID.randomUUID();
                    UUID bidder = UUID.randomUUID();
                    policy.extendAcceptedBid(auction, bidder);
                    verify(auctions).extendEndAt(auction, bidder, 5, 30);

                    clearInvocations(auctions);
                    source.put("nexus.auction.anti-sniping-enabled", "false");
                    context.getBean(RefreshScope.class).refreshAll();
                    policy.extendAcceptedBid(auction, bidder);
                    verifyNoInteractions(auctions);
                    assertThat(proxy.isAntiSnipingEnabled()).isFalse();

                    source.put("nexus.auction.anti-sniping-enabled", "true");
                    source.put("nexus.auction.anti-sniping-extension-minutes", "2");
                    source.put("nexus.auction.anti-sniping-max-extension-minutes", "7");
                    context.getBean(RefreshScope.class).refreshAll();
                    policy.extendAcceptedBid(auction, bidder);
                    verify(auctions).extendEndAt(auction, bidder, 2, 7);
                    assertThat(context.getBean(AntiSnipingPolicy.class)).isSameAs(policy);
                    assertThat(context.getBean(AuctionProperties.class)).isSameAs(proxy);
                    assertThat(context.getStartupDate()).isEqualTo(startup);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PolicyConsumerConfiguration {
        @Bean
        AuctionRepository auctions() {
            return mock(AuctionRepository.class);
        }

        @Bean
        AntiSnipingPolicy antiSnipingPolicy(AuctionRepository auctions, AuctionProperties properties) {
            return new AntiSnipingPolicy(auctions, properties);
        }
    }
}
