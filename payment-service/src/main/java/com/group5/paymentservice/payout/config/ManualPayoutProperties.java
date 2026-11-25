package com.group5.paymentservice.payout.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Getter
@Setter
@Validated
@ConfigurationProperties("nexus.payout.manual")
public class ManualPayoutProperties {
    private boolean enabled;
    @NotNull private Duration claimLease = Duration.ofMinutes(5);
    @NotNull private Duration qrDeadline = Duration.ofMinutes(10);
    @NotNull private ZoneId bankBusinessZone = ZoneId.of("Asia/Ho_Chi_Minh");

    @NotNull
    @DecimalMin("1")
    private BigDecimal maxAmountVnd = new BigDecimal("499999999");

    @NotNull private List<@Valid SourceAccount> sourceAccounts = List.of();
    private String sourceAccountsJson = "";

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<SourceAccount> parsedSourceAccounts = List.of();

    @NotNull @Valid private Evidence evidence = new Evidence();
    @NotNull private Sepay sepay = new Sepay();

    public record SourceAccount(@NotBlank String id, @NotBlank String alias) {}

    /**
     * Native/indexed configuration wins; JSON also supports IDE runs with ordinary env variables.
     */
    public List<SourceAccount> getSourceAccounts() {
        return sourceAccounts != null && !sourceAccounts.isEmpty()
                ? sourceAccounts
                : parsedSourceAccounts;
    }

    public void setSourceAccountsJson(String value) {
        // Validate even when native entries take precedence; malformed deployment config is an
        // error.
        var accounts = parseSourceAccounts(value);
        sourceAccountsJson = value == null ? "" : value;
        parsedSourceAccounts = accounts;
    }

    private static List<SourceAccount> parseSourceAccounts(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            if (value.length() > 65536) throw new IllegalArgumentException();
            var json =
                    JsonMapper.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .build();
            var root = json.readTree(value);
            if (root == null || !root.isArray() || root.size() > 100) {
                throw new IllegalArgumentException();
            }
            var accounts = new ArrayList<SourceAccount>();
            for (var entry : root) {
                if (!entry.isObject()
                        || entry.size() != 2
                        || !entry.has("id")
                        || !entry.has("alias")
                        || !entry.get("id").isString()
                        || !entry.get("alias").isString()) {
                    throw new IllegalArgumentException();
                }
                accounts.add(
                        new SourceAccount(
                                entry.get("id").stringValue(), entry.get("alias").stringValue()));
            }
            if (!validSourceAccounts(accounts)) throw new IllegalArgumentException();
            return List.copyOf(accounts);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Manual source accounts must be a JSON array of id/alias objects");
        }
    }

    @AssertTrue(
            message =
                    "Manual payout durations must be positive, source aliases unique and VND limit"
                            + " integral")
    public boolean isValidManualConfiguration() {
        if (!positive(claimLease)
                || !positive(qrDeadline)
                || maxAmountVnd == null
                || maxAmountVnd.signum() <= 0
                || maxAmountVnd.stripTrailingZeros().scale() > 0
                || maxAmountVnd.compareTo(new BigDecimal("499999999")) > 0
                || sourceAccounts == null) {
            return false;
        }
        return validSourceAccounts(getSourceAccounts());
    }

    private static boolean validSourceAccounts(List<SourceAccount> accounts) {
        var ids = new HashSet<String>();
        return accounts.stream()
                .allMatch(
                        account ->
                                account != null
                                        && account.id() != null
                                        && account.id().matches("[A-Za-z0-9_-]{1,64}")
                                        && account.alias() != null
                                        && !account.alias().isBlank()
                                        && account.alias().length() <= 100
                                        && ids.add(account.id()));
    }

    public String sepaySourceAccount() {
        String id = sepay.getSourceAccountId();
        if (id != null && !id.isBlank()) return isAuthorizedSourceAccount(id) ? id : null;
        var accounts = getSourceAccounts();
        return accounts.size() == 1 ? accounts.get(0).id() : null;
    }

    public boolean isAuthorizedSourceAccount(String id) {
        return id != null
                && getSourceAccounts().stream().anyMatch(account -> account.id().equals(id));
    }

    @Getter
    @Setter
    public static class Sepay {
        private boolean reconciliationEnabled = true;
        private String bankAccount = "";
        private String sourceAccountId = "";
    }

    @Getter
    @Setter
    public static class Evidence {
        @jakarta.validation.constraints.Min(10485760)
        @jakarta.validation.constraints.Max(10485760)
        private long maxFileBytes = 10485760;

        private String url = "";
        private String key = "";
        private String fallbackUrl = "";
        private String fallbackKey = "";

        /**
         * A blank optional Payment override still uses the project's existing backend
         * configuration.
         */
        public String getUrl() {
            return url == null || url.isBlank() ? fallbackUrl : url;
        }

        public String getKey() {
            return key == null || key.isBlank() ? fallbackKey : key;
        }

        @NotBlank private String bucket = "payout-evidence";
        @NotNull private Duration requestTimeout = Duration.ofSeconds(10);
        @NotNull private Duration orphanGrace = Duration.ofHours(24);
        @NotNull private Duration cleanupInterval = Duration.ofHours(1);
        private boolean cleanupEnabled = true;

        @AssertTrue(
                message =
                        "Evidence needs an HTTPS project URL, a safe bucket name and a positive"
                                + " timeout")
        public boolean isValidEvidenceConfiguration() {
            if (!positive(requestTimeout)
                    || !positive(cleanupInterval)
                    || orphanGrace == null
                    || orphanGrace.compareTo(Duration.ofHours(1)) < 0
                    || bucket == null
                    || !bucket.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
                return false;
            }
            String effectiveUrl = getUrl();
            if (effectiveUrl == null || effectiveUrl.isBlank()) return true;
            try {
                URI uri = URI.create(effectiveUrl);
                return "https".equals(uri.getScheme())
                        && uri.getHost() != null
                        && uri.getRawUserInfo() == null
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() == null
                        && (uri.getRawPath() == null
                                || uri.getRawPath().isEmpty()
                                || "/".equals(uri.getRawPath()));
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        public boolean configured() {
            String effectiveUrl = getUrl(), effectiveKey = getKey();
            return isValidEvidenceConfiguration()
                    && effectiveUrl != null
                    && !effectiveUrl.isBlank()
                    && effectiveKey != null
                    && !effectiveKey.isBlank()
                    && !effectiveKey.contains("\r")
                    && !effectiveKey.contains("\n");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
