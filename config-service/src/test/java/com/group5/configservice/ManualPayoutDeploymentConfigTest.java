package com.group5.configservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deployment wiring uses synthetic values only; it neither reads .env nor contacts Supabase/Docker.
 */
class ManualPayoutDeploymentConfigTest {
    private static final String PREFIX = "nexus.payout.manual.evidence.";

    @Test
    void defaultManualEvidenceIsPrivateAndUnconfiguredWithoutDeploymentCredentials() {
        var environment = environment(paymentYaml(), Map.of());
        assertThat(environment.getProperty(PREFIX + "url")).isEmpty();
        assertThat(environment.getProperty(PREFIX + "key")).isEmpty();
        assertThat(environment.getProperty(PREFIX + "bucket")).isEqualTo("payout-evidence");
        assertThat(environment.getProperty(PREFIX + "max-file-bytes")).isEqualTo("10485760");
        assertThat(environment.getProperty("nexus.payout.execution-mode")).isEqualTo("AUTO");
        assertThat(environment.getProperty("nexus.payout.jobs-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("nexus.payout.manual.enabled")).isEqualTo("false");
        assertThat(paymentYaml().stringPropertyNames())
                .noneMatch(name -> name.startsWith("nexus.payout-evidence"));
    }

    @Test
    void canonicalStorageNamesOverrideTheAuthorizedSharedProjectServerKey() {
        var environment =
                environment(
                        paymentYaml(),
                        Map.of(
                                "SUPABASE_URL",
                                "https://shared-test.supabase.co",
                                "PAYOUT_EVIDENCE_SUPABASE_URL",
                                "https://payout-test.supabase.co",
                                "PAYOUT_EVIDENCE_SUPABASE_SERVER_KEY",
                                "sb_secret_synthetic_test_only",
                                "PAYOUT_EVIDENCE_MAX_FILE_BYTES",
                                "10485760",
                                "SUPABASE_SERVICE_KEY",
                                "catalog-test-key-only"));
        assertThat(environment.getProperty(PREFIX + "url"))
                .isEqualTo("https://payout-test.supabase.co");
        assertThat(environment.getProperty(PREFIX + "key"))
                .isEqualTo("sb_secret_synthetic_test_only");
        assertThat(environment.getProperty(PREFIX + "max-file-bytes")).isEqualTo("10485760");
        var legacy =
                environment(
                        paymentYaml(),
                        Map.of(
                                "SUPABASE_URL",
                                "https://shared-test.supabase.co",
                                "SUPABASE_SERVICE_KEY",
                                "shared-project-test-only"));
        assertThat(legacy.getProperty(PREFIX + "url")).isEmpty();
        assertThat(legacy.getProperty(PREFIX + "key")).isEmpty();
        assertThat(legacy.getProperty(PREFIX + "fallback-url"))
                .isEqualTo("https://shared-test.supabase.co");
        assertThat(legacy.getProperty(PREFIX + "fallback-key"))
                .isEqualTo("shared-project-test-only");
    }

    @Test
    void paymentContainerMapsTheOfficialGuideSettingsThroughCentralConfiguration()
            throws Exception {
        var deployment = new HashMap<String, Object>();
        deployment.put("PAYOUT_EXECUTION_MODE", "MANUAL");
        deployment.put("PAYOUT_JOBS_ENABLED", "false");
        deployment.put("PAYOUT_MANUAL_ENABLED", "true");
        deployment.put("PAYOUT_ACCOUNT_ENCRYPTION_KEY", "synthetic-aes-test-only");
        deployment.put(
                "PAYOUT_MANUAL_SOURCE_ACCOUNTS_JSON",
                "[{\"id\":\"platform-main\",\"alias\":\"Platform test source\"}]");
        deployment.put("PAYOUT_EVIDENCE_SUPABASE_URL", "https://payout-test.supabase.co");
        deployment.put("PAYOUT_EVIDENCE_SUPABASE_SERVER_KEY", "sb_secret_synthetic_test_only");
        deployment.put("PAYOUT_EVIDENCE_BUCKET", "payout-evidence");
        deployment.put("PAYOUT_EVIDENCE_MAX_FILE_BYTES", "10485760");
        deployment.put("SUPABASE_SERVICE_KEY", "catalog-test-key-only");
        Map<String, Object> container = paymentContainer(deployment);
        assertThat(container).doesNotContainKeys("SUPABASE_PUBLISHABLE_KEY");
        assertThat(container.get("SUPABASE_SERVICE_KEY")).isEqualTo("catalog-test-key-only");
        var environment = environment(paymentYaml(), container);
        assertThat(environment.getProperty(PREFIX + "url"))
                .isEqualTo("https://payout-test.supabase.co");
        assertThat(environment.getProperty(PREFIX + "key"))
                .isEqualTo("sb_secret_synthetic_test_only");
        assertThat(environment.getProperty("nexus.payout.account-encryption-key"))
                .isEqualTo("synthetic-aes-test-only");
        assertThat(environment.getProperty("nexus.payout.execution-mode")).isEqualTo("MANUAL");
        assertThat(environment.getProperty("nexus.payout.jobs-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("nexus.payout.manual.enabled")).isEqualTo("true");
        var aliases =
                JsonMapper.builder()
                        .build()
                        .readTree(container.get("SPRING_APPLICATION_JSON").toString())
                        .get("nexus")
                        .get("payout")
                        .get("manual")
                        .get("source-accounts");
        var jsonProperties = new Properties();
        jsonProperties.setProperty(
                "nexus.payout.manual.source-accounts[0].id", aliases.get(0).get("id").asString());
        jsonProperties.setProperty(
                "nexus.payout.manual.source-accounts[0].alias",
                aliases.get(0).get("alias").asString());
        environment
                .getPropertySources()
                .addFirst(
                        new PropertiesPropertySource(
                                "synthetic-spring-application-json", jsonProperties));
        var bound =
                Binder.get(environment)
                        .bind("nexus.payout.manual.source-accounts[0]", SourceAlias.class)
                        .orElseThrow(() -> new AssertionError("Source alias did not bind"));
        assertThat(bound).isEqualTo(new SourceAlias("platform-main", "Platform test source"));
    }

    @Test
    void blankContainerDefaultsDoNotRequireARealBucketAndTheExampleContainsNoKeys()
            throws Exception {
        Map<String, Object> container = paymentContainer(Map.of());
        assertThat(container.get("PAYOUT_EVIDENCE_SUPABASE_URL")).isEqualTo("");
        assertThat(container.get("PAYOUT_EVIDENCE_SUPABASE_SERVER_KEY")).isEqualTo("");
        var aliases =
                JsonMapper.builder()
                        .build()
                        .readTree(container.get("SPRING_APPLICATION_JSON").toString())
                        .get("nexus")
                        .get("payout")
                        .get("manual")
                        .get("source-accounts");
        assertThat(aliases.isArray()).isTrue();
        assertThat(aliases.size()).isZero();
        var example = Files.readAllLines(Path.of("../infra/local-dev/.env.example"));
        assertThat(example)
                .contains(
                        "PAYOUT_EVIDENCE_BUCKET=payout-evidence",
                        "PAYOUT_EXECUTION_MODE=AUTO",
                        "PAYOUT_MANUAL_SOURCE_ACCOUNTS_JSON=[]",
                        "PAYOUT_ACCOUNT_ENCRYPTION_KEY=",
                        "PAYOUT_JOBS_ENABLED=false",
                        "PAYOUT_MANUAL_ENABLED=false");
    }

    @Test
    void thePaymentContainerCanReuseTheAuthorizedProjectKeyWithoutChangingTheCatalogBucket() {
        Map<String, Object> container =
                paymentContainer(
                        Map.of(
                                "SUPABASE_URL",
                                "https://shared-test.supabase.co",
                                "SUPABASE_SERVICE_KEY",
                                "shared-project-test-only",
                                "SUPABASE_BUCKET",
                                "product-images"));
        assertThat(container.get("PAYOUT_EVIDENCE_SUPABASE_URL"))
                .isEqualTo("https://shared-test.supabase.co");
        assertThat(container.get("PAYOUT_EVIDENCE_SUPABASE_SERVER_KEY"))
                .isEqualTo("shared-project-test-only");
        assertThat(container.get("PAYOUT_EVIDENCE_BUCKET")).isEqualTo("payout-evidence");
        assertThat(container).doesNotContainKey("SUPABASE_BUCKET");
        assertThat(environment(paymentYaml(), container).getProperty(PREFIX + "key"))
                .isEqualTo("shared-project-test-only");
    }

    @Test
    void directJavaReceivesSourceAliasesFromTheOrdinaryEnvironmentVariable() {
        String suppliedJson = "[{\"id\":\"platform-main\",\"alias\":\"Platform test source\"}]";
        var environment =
                environment(
                        paymentYaml(), Map.of("PAYOUT_MANUAL_SOURCE_ACCOUNTS_JSON", suppliedJson));
        assertThat(environment.getProperty("SPRING_APPLICATION_JSON")).isNull();
        var binding =
                Binder.get(environment)
                        .bind("nexus.payout.manual", SourceAliasJson.class)
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Manual source JSON did not bind from central"
                                                    + " configuration"));
        assertThat(binding.sourceAccountsJson()).isEqualTo(suppliedJson);
        var account = JsonMapper.builder().build().readTree(binding.sourceAccountsJson()).get(0);
        assertThat(new SourceAlias(account.get("id").asString(), account.get("alias").asString()))
                .isEqualTo(new SourceAlias("platform-main", "Platform test source"));
        assertThat(
                        environment(paymentYaml(), Map.of())
                                .getProperty("nexus.payout.manual.source-accounts-json"))
                .isEmpty();
    }

    private static Properties paymentYaml() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("config/payment-service.yml"));
        return yaml.getObject();
    }

    private static Map<String, Object> paymentContainer(Map<String, Object> supplied) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../infra/local-dev/compose.yaml"));
        var context = environment(new Properties(), supplied);
        var values = new HashMap<String, Object>();
        String prefix = "services.payment-service.environment.";
        yaml.getObject()
                .forEach(
                        (key, value) -> {
                            String name = key.toString();
                            if (name.startsWith(prefix))
                                values.put(
                                        name.substring(prefix.length()),
                                        context.resolveRequiredPlaceholders(
                                                composeDefaults(value.toString())));
                        });
        return values;
    }

    private static StandardEnvironment environment(Properties yaml, Map<String, Object> values) {
        var environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addLast(new PropertiesPropertySource("central-yaml", yaml));
        // Populate unrelated mandatory Compose variables only with explicit synthetic test values.
        var supplied =
                new HashMap<String, Object>(
                        Map.of(
                                "TZ",
                                "Asia/Ho_Chi_Minh",
                                "DB_USERNAME",
                                "test-user",
                                "DB_PASSWORD",
                                "test-password"));
        supplied.putAll(values);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("synthetic-deployment", supplied));
        return environment;
    }

    private static String composeDefaults(String value) {
        // Spring and Compose support the same nested fallback shape, with ':' vs ':-' delimiters.
        String emptyFallback =
                Pattern.compile("(\\$\\{[A-Z0-9_]+):-").matcher(value).replaceAll("$1:");
        return Pattern.compile("(\\$\\{[A-Z0-9_]+)-").matcher(emptyFallback).replaceAll("$1:");
    }

    public record SourceAlias(String id, String alias) {}

    public record SourceAliasJson(String sourceAccountsJson) {}
}
