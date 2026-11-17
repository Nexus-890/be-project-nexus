package com.group5.configservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** Nạp YAML bằng Config Data thật để kiểm tra profile, không cần server, database hoặc Docker. */
class AuctionStagingConfigTest {

    private ApplicationContextRunner profile(String service, String profile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // Không để biến môi trường của máy chạy test thay các giá trị mặc định đang kiểm tra.
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.name=" + service,
                        "spring.config.location=classpath:/config/",
                        "spring.profiles.active=" + profile);
    }

    @ParameterizedTest(name = "profile {0} giữ 60/5/30 phút")
    @ValueSource(strings = {"default", "local", "production"})
    void ordinaryProfilesKeepTheOriginalDurationAndAntiSnipingDefaults(String profile) {
        profile("auction-service", profile).run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("nexus.auction.min-duration-minutes", Integer.class)).isEqualTo(60);
            assertThat(environment.getProperty("nexus.auction.anti-sniping-extension-minutes", Integer.class)).isEqualTo(5);
            assertThat(environment.getProperty("nexus.auction.anti-sniping-max-extension-minutes", Integer.class)).isEqualTo(30);
        });
    }

    @Test
    void stagingLoadsTheDemoWindowAndKeepsAntiSnipingEnabled() {
        profile("auction-service", "staging").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("nexus.auction.min-duration-minutes", Integer.class)).isEqualTo(1);
            assertThat(environment.getProperty("nexus.auction.anti-sniping-enabled", Boolean.class)).isTrue();
            assertThat(environment.getProperty("nexus.auction.anti-sniping-extension-minutes", Integer.class)).isEqualTo(1);
            assertThat(environment.getProperty("nexus.auction.anti-sniping-max-extension-minutes", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void stagingInheritsTheOtherAuctionGuardsFromTheBaseYaml() {
        profile("auction-service", "staging").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("nexus.auction.max-duration-hours", Integer.class)).isEqualTo(168);
            assertThat(environment.getProperty("nexus.auction.max-active-per-seller", Integer.class)).isEqualTo(5);
            assertThat(environment.getProperty("nexus.auction.payment-deadline-hours", Integer.class)).isEqualTo(24);
        });
    }

    @Test
    void anotherServiceDoesNotReceiveTheAuctionDemoOverrides() {
        profile("commerce-service", "staging").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("nexus.auction.min-duration-minutes")).isNull();
            assertThat(context.getEnvironment().getProperty("nexus.auction.anti-sniping-extension-minutes")).isNull();
            assertThat(context.getEnvironment().getProperty("nexus.auction.anti-sniping-max-extension-minutes")).isNull();
        });
    }
}
