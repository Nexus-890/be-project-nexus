package com.group5.paymentservice.payout.service;

import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.qr.VietQrPayoutEncoder;
import com.group5.paymentservice.payout.repository.ManualPayoutRepository;
import com.group5.paymentservice.sellerbank.security.BankAccountCipher;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Pure readiness evaluation shared by commands and read-only capability projections. */
@Service
@RequiredArgsConstructor
public class ManualPayoutReadinessService {
    private final ManualPayoutRepository repository;
    private final ManualPayoutProperties settings;
    private final BankAccountCipher cipher;

    public String blockedReason(Map<String, Object> payout) {
        if (settings.getSourceAccounts().isEmpty()) return "SOURCE_ACCOUNT_NOT_CONFIGURED";
        boolean funded = repository.funded((UUID) payout.get("id"));
        String initial = blockedBeforeBank(payout, funded);
        if (initial != null) return initial;
        return blockedReason(
                payout, repository.bank((UUID) payout.get("seller_id")).orElse(null), funded);
    }

    /** List queries supply both facts in bulk, avoiding a database read per displayed payout. */
    public String blockedReason(
            Map<String, Object> payout, Map<String, Object> bank, boolean funded) {
        String initial = blockedBeforeBank(payout, funded);
        if (initial != null) return initial;
        if (bank == null) return "BANK_NOT_READY";
        try {
            String account = cipher.decrypt((String) bank.get("account_number_encrypted"));
            if (!account.matches("[0-9]{4,25}")
                    || !VietQrPayoutEncoder.supportsBank((String) bank.get("bank_code")))
                return "UNSUPPORTED_BANK_ACCOUNT";
        } catch (IllegalStateException failure) {
            return "BANK_DATA_UNAVAILABLE";
        }
        return null;
    }

    private String blockedBeforeBank(Map<String, Object> payout, boolean funded) {
        if (settings.getSourceAccounts().isEmpty()) return "SOURCE_ACCOUNT_NOT_CONFIGURED";
        if (!funded) return "FUNDING_OR_REFUND_NOT_READY";
        BigDecimal amount = (BigDecimal) payout.get("net_amount");
        if (!"VND".equals(payout.get("currency"))
                || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 0
                || amount.compareTo(settings.getMaxAmountVnd()) > 0) return "UNSUPPORTED_AMOUNT";
        return null;
    }
}
