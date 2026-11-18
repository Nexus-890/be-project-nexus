package com.group5.paymentservice.payout.service;

import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.provider.PayoutAttemptKey;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.repository.PayoutDispatchRepository;
import com.group5.paymentservice.sellerbank.security.BankAccountCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static com.group5.paymentservice.payout.service.PayoutService.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutDispatchService {
    private final PayoutDispatchRepository dispatches;
    private final TransactionTemplate transactions;
    private final PayoutService payouts;
    private final PayoutGateway gateway;
    private final BankAccountCipher cipher;
    private final PayoutProperties properties;

    // Commit trạng thái và snapshot trước khi gọi provider để có thể phục hồi sau khi process dừng.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean dispatchOne() {
        PayoutGateway.Transfer transfer = transactions.execute(status -> claim());
        if (transfer == null) return false;
        PayoutGateway.Result result;
        try {
            result = gateway.send(transfer);
        } catch (RuntimeException failure) {
            log.warn("Payout send unresolved: payoutId={} attempt={}", transfer.payoutId(), transfer.attempt());
            result = new PayoutGateway.Result(PayoutGateway.State.UNKNOWN, null, "NETWORK_UNRESOLVED");
        }
        payouts.apply(transfer.payoutId(), transfer.attempt(), gateway.provider(), result, false);
        return true;
    }

    private PayoutGateway.Transfer claim() {
        var due = dispatches.claimNextDue();
        if (due.isEmpty()) return null;
        var payout = due.get();
        UUID id = (UUID) payout.get("id");
        var account = dispatches.findVerifiedDefault((UUID) payout.get("seller_id"));
        if (account.isEmpty()) {
            dispatches.holdWithoutRecipient(id);
            payouts.history(payouts.locked(id), "HELD", "PENDING", "ON_HOLD", SYSTEM, null, null, null, null);
            return null;
        }
        var bank = account.get();
        // Giải mã trước khi đổi trạng thái; lỗi khóa mã hóa không được làm mất lượt xử lý.
        String accountNumber = cipher.decrypt((String) bank.get("account_number_encrypted"));
        int attempt = integer(payout, "attempt_count") + 1;
        dispatches.startAttempt(id, attempt, bank, gateway.provider());
        payouts.history(payouts.locked(id), "SENT", "PENDING", "PROCESSING", SYSTEM, null, null, null, null);
        return new PayoutGateway.Transfer(id, attempt, PayoutAttemptKey.of(id, attempt), (BigDecimal) payout.get("net_amount"),
                str(payout, "currency"), str(bank, "bank_code"), accountNumber, str(bank, "account_holder_name"));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcile() {
        // Quét bù để payout không bị giữ mãi nếu sự kiện verify đến trước lúc tạo ON_HOLD.
        for (UUID sellerId : dispatches.findHeldSellersWithVerifiedDefault()) payouts.releaseHeld(sellerId);
        for (int processed = 0; processed < 100; processed++) {
            var now = OffsetDateTime.now(ZoneOffset.UTC);
            var claimed = transactions.execute(status -> dispatches.claimNextReconciliation(gateway.provider(),
                    now.minus(properties.getProviderTimeout()), now.plus(reconciliationLease())));
            if (claimed == null || claimed.isEmpty()) break;
            var payout = claimed.get();
            UUID id = (UUID) payout.get("id");
            int attempt = integer(payout, "attempt_count");
            try {
                var result = gateway.lookup(PayoutAttemptKey.of(id, attempt));
                if (result != null && result.state() == PayoutGateway.State.NOT_FOUND) {
                    // Chỉ gửi lại đúng attempt/key và snapshot cũ khi provider xác nhận chưa nhận lệnh.
                    var unresolved = dispatches.findUnresolvedAttempt(id, attempt);
                    if (unresolved.isEmpty()) continue;
                    result = gateway.send(frozenTransfer(unresolved.get(), attempt));
                }
                payouts.apply(id, attempt, gateway.provider(), result, true);
            } catch (RuntimeException failure) {
                log.warn("Payout reconciliation retained: payoutId={} attempt={}", id, attempt);
            }
        }
    }

    private Duration reconciliationLease() {
        // Mỗi lần chỉ nhận một dòng; lease đủ cho lookup và tối đa một lần gửi lại cùng key.
        Duration networkWindow = properties.getProviderTimeout().multipliedBy(2).plusSeconds(5);
        Duration interval = properties.getReconcileInterval();
        return interval.compareTo(networkWindow) >= 0 ? interval : networkWindow;
    }

    private PayoutGateway.Transfer frozenTransfer(Map<String, Object> payout, int attempt) {
        UUID id = (UUID) payout.get("id");
        return new PayoutGateway.Transfer(id, attempt, PayoutAttemptKey.of(id, attempt), (BigDecimal) payout.get("net_amount"),
                str(payout, "currency"), str(payout, "recipient_bank_code"),
                cipher.decrypt(str(payout, "recipient_account_encrypted")), str(payout, "recipient_holder_name"));
    }
}
