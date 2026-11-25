package com.group5.paymentservice.payout.service;

import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.model.BankTransfer;
import com.group5.paymentservice.payout.repository.ManualPayoutRepository;
import com.group5.paymentservice.shared.config.SepayProperties;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ManualPayoutBankReconciliationService {
    private static final Logger log =
            LoggerFactory.getLogger(ManualPayoutBankReconciliationService.class);
    private static final Pattern REFERENCE = Pattern.compile("(?i)NXP([0-9a-f]{20})");
    private static final DateTimeFormatter SEPAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PROVIDER = "SEPAY";

    private final ManualPayoutRepository repository;
    private final ManualPayoutService manual;
    private final ManualPayoutProperties settings;
    private final SepayProperties sepay;

    @Transactional
    public String reconcile(SepayWebhookRequest request) {
        if (!settings.getSepay().isReconciliationEnabled() || request.id() == null) return null;
        var transfer =
                new BankTransfer(
                        PROVIDER,
                        request.id().toString(),
                        request.accountNumber(),
                        request.transferAmount(),
                        request.content(),
                        request.referenceCode(),
                        transactionAt(request.transactionDate()));
        String expected = account(payoutAccount());
        if (expected.isEmpty() || !expected.equals(account(request.accountNumber())))
            return record(transfer, "WRONG_ACCOUNT", "Account is not the payout source account");
        for (String reference : references(request)) {
            var attempt = repository.attemptByReference(reference);
            if (attempt.isPresent())
                return manual.applyBankTransfer(
                        (UUID) attempt.get().get("payout_id"),
                        (UUID) attempt.get().get("id"),
                        transfer);
        }
        log.warn("Outgoing SePay transfer {} carries no payout reference", transfer.transactionId());
        return record(transfer, "UNMATCHED", null);
    }

    private String record(BankTransfer transfer, String outcome, String detail) {
        repository.recordBankTransaction(
                ManualPayoutService.bankRow(transfer, null, null, outcome, detail));
        return outcome;
    }

    private String payoutAccount() {
        String configured = settings.getSepay().getBankAccount();
        return configured == null || configured.isBlank() ? sepay.bankAccount() : configured;
    }

    private OffsetDateTime transactionAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value.trim(), SEPAY_TIME)
                    .atZone(settings.getBankBusinessZone())
                    .toOffsetDateTime();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static String account(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
    }

    static Set<String> references(SepayWebhookRequest request) {
        return Stream.of(request.code(), request.content(), request.description())
                .filter(Objects::nonNull)
                .flatMap(text -> REFERENCE.matcher(text).results())
                .map(match -> "NXP" + match.group(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
