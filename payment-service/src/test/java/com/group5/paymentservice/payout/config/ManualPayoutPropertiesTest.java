package com.group5.paymentservice.payout.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZoneId;

class ManualPayoutPropertiesTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaultsDisableNewManualWorkAndDoNotRequireAProvisionedBucket() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    var settings = context.getBean(ManualPayoutProperties.class);
                    assertThat(settings.isEnabled()).isFalse();
                    assertThat(settings.getClaimLease()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(settings.getQrDeadline()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(settings.getBankBusinessZone())
                            .isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
                    assertThat(settings.getEvidence().configured()).isFalse();
                    assertThat(settings.getEvidence().getBucket()).isEqualTo("payout-evidence");
                    assertThat(settings.getEvidence().getMaxFileBytes()).isEqualTo(10485760);
                });
    }

    @Test
    void blankPaymentOverridesUseExistingBackendSupabaseConfiguration() {
        runner.withPropertyValues(
                        "nexus.payout.manual.evidence.url= ",
                        "nexus.payout.manual.evidence.key= ",
                        "nexus.payout.manual.evidence.fallback-url=https://shared.example.test",
                        "nexus.payout.manual.evidence.fallback-key=sb_secret_shared_test_only")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var evidence =
                                    context.getBean(ManualPayoutProperties.class).getEvidence();
                            assertThat(evidence.getUrl()).isEqualTo("https://shared.example.test");
                            assertThat(evidence.getKey()).isEqualTo("sb_secret_shared_test_only");
                            assertThat(evidence.configured()).isTrue();
                        });
    }

    @Test
    void dedicatedPaymentOverridesTakePrecedenceOverSharedBackendSettings() {
        runner.withPropertyValues(
                        "nexus.payout.manual.evidence.url=https://payment.example.test",
                        "nexus.payout.manual.evidence.key=sb_secret_payment_test_only",
                        "nexus.payout.manual.evidence.fallback-url=https://shared.example.test",
                        "nexus.payout.manual.evidence.fallback-key=sb_secret_shared_test_only")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var evidence =
                                    context.getBean(ManualPayoutProperties.class).getEvidence();
                            assertThat(evidence.getUrl()).isEqualTo("https://payment.example.test");
                            assertThat(evidence.getKey()).isEqualTo("sb_secret_payment_test_only");
                            assertThat(evidence.configured()).isTrue();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shared.example.test", "not a valid URI"})
    void anInvalidEffectiveFallbackUrlFailsStartupAndNeverCountsAsConfigured(String url) {
        runner.withPropertyValues(
                        "nexus.payout.manual.evidence.url=",
                        "nexus.payout.manual.evidence.fallback-url=" + url,
                        "nexus.payout.manual.evidence.fallback-key=sb_secret_shared_test_only")
                .run(context -> assertThat(context).hasFailed());
        var evidence = new ManualPayoutProperties.Evidence();
        evidence.setFallbackUrl(url);
        evidence.setFallbackKey("sb_secret_shared_test_only");
        assertThat(evidence.isValidEvidenceConfiguration()).isFalse();
        assertThat(evidence.configured()).isFalse();
    }

    @Test
    void exposesOnlyConfiguredSourceAliases() {
        runner.withPropertyValues(
                        "nexus.payout.manual.source-accounts[0].id=platform-main",
                        "nexus.payout.manual.source-accounts[0].alias=Platform operating account")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings = context.getBean(ManualPayoutProperties.class);
                            assertThat(settings.isAuthorizedSourceAccount("platform-main"))
                                    .isTrue();
                            assertThat(settings.isAuthorizedSourceAccount("invented-source"))
                                    .isFalse();
                        });
    }

    @Test
    void jsonEnvironmentConfigurationProvidesTheSameAliasesToDirectJavaRuns() {
        runner.withPropertyValues(
                        "nexus.payout.manual.source-accounts-json=[{\"id\":\"platform-main\",\"alias\":\"Platform"
                            + " operating account\"},{\"id\":\"platform-backup\",\"alias\":\"Backup"
                            + " payout account\"}]")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings = context.getBean(ManualPayoutProperties.class);
                            assertThat(settings.getSourceAccounts())
                                    .containsExactly(
                                            new ManualPayoutProperties.SourceAccount(
                                                    "platform-main", "Platform operating account"),
                                            new ManualPayoutProperties.SourceAccount(
                                                    "platform-backup", "Backup payout account"));
                            assertThat(settings.isAuthorizedSourceAccount("platform-main"))
                                    .isTrue();
                            assertThat(settings.isAuthorizedSourceAccount("platform-backup"))
                                    .isTrue();
                            assertThat(settings.isAuthorizedSourceAccount("invented-source"))
                                    .isFalse();
                        });
    }

    @Test
    void nativeIndexedSourceAccountsTakePriorityOverAValidJsonFallback() {
        runner.withPropertyValues(
                        "nexus.payout.manual.source-accounts[0].id=native-main",
                        "nexus.payout.manual.source-accounts[0].alias=Native account",
                        "nexus.payout.manual.source-accounts-json=[{\"id\":\"json-fallback\",\"alias\":\"JSON"
                            + " account\"}]")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings = context.getBean(ManualPayoutProperties.class);
                            assertThat(settings.getSourceAccounts())
                                    .containsExactly(
                                            new ManualPayoutProperties.SourceAccount(
                                                    "native-main", "Native account"));
                            assertThat(settings.isAuthorizedSourceAccount("native-main")).isTrue();
                            assertThat(settings.isAuthorizedSourceAccount("json-fallback"))
                                    .isFalse();
                        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "[]"})
    void anAbsentOrEmptyJsonListDoesNotCreatePlaceholderAliases(String value) {
        runner.withPropertyValues("nexus.payout.manual.source-accounts-json=" + value)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var settings = context.getBean(ManualPayoutProperties.class);
                            assertThat(settings.getSourceAccounts()).isEmpty();
                            assertThat(settings.isAuthorizedSourceAccount("")).isFalse();
                        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-json",
                "null",
                "{}",
                "[null]",
                "[\"main\"]",
                "[{\"id\":\"main\"}]",
                "[{\"id\":12,\"alias\":\"Main\"}]",
                "[{\"id\":\"main\",\"alias\":null}]",
                "[{\"id\":\"main\",\"alias\":\"Main\",\"extra\":\"unexpected\"}]",
                "[{\"id\":\"main\",\"id\":\"other\",\"alias\":\"Main\"}]",
                "[{\"id\":\"main\",\"alias\":\"Main\"}] []",
                "[{\"id\":\"../unsafe\",\"alias\":\"Main\"}]",
                "[{\"id\":\"main\",\"alias\":\" \"}]",
                "[{\"id\":\"main\",\"alias\":\"One\"},{\"id\":\"main\",\"alias\":\"Two\"}]"
            })
    void malformedOrInvalidSourceJsonFailsStartupInsteadOfSilentlyDisablingSources(String value) {
        runner.withPropertyValues("nexus.payout.manual.source-accounts-json=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedJsonIsStillRejectedWhenNativeEntriesHavePriority() {
        runner.withPropertyValues(
                        "nexus.payout.manual.source-accounts[0].id=native-main",
                        "nexus.payout.manual.source-accounts[0].alias=Native account",
                        "nexus.payout.manual.source-accounts-json=malformed")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "claim-lease=0s",
                "qr-deadline=-1m",
                "max-amount-vnd=0",
                "max-amount-vnd=1.25",
                "max-amount-vnd=500000000",
                "evidence.request-timeout=0s",
                "evidence.orphan-grace=30m",
                "evidence.cleanup-interval=0s",
                "evidence.max-file-bytes=10485759",
                "evidence.max-file-bytes=10485761",
                "evidence.url=http://storage.example.test",
                "evidence.url=https://storage.example.test?token=unsafe",
                "evidence.bucket=../public"
            })
    void rejectsUnsafeSettings(String setting) {
        runner.withPropertyValues("nexus.payout.manual." + setting)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void duplicateSourceIdsAreRejected() {
        runner.withPropertyValues(
                        "nexus.payout.manual.source-accounts[0].id=main",
                        "nexus.payout.manual.source-accounts[0].alias=Main",
                        "nexus.payout.manual.source-accounts[1].id=main",
                        "nexus.payout.manual.source-accounts[1].alias=Other")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ManualPayoutProperties.class)
    static class Config {}
}
