package com.group5.paymentservice.payout.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_events.Topics;
import com.group5.common_outbox.OutboxWriter;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.PayoutProperties;
import com.group5.paymentservice.payout.provider.PayoutAttemptKey;
import com.group5.paymentservice.payout.provider.PayoutGateway;
import com.group5.paymentservice.payout.repository.PayoutHistoryRepository;
import com.group5.paymentservice.payout.repository.PayoutMovementRepository;
import com.group5.paymentservice.payout.repository.PayoutRepository;
import com.group5.paymentservice.sellerbank.event.BankAccountVerified;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {
    static final UUID SYSTEM = new UUID(0, 0);
    private final PayoutRepository payouts;
    private final PayoutHistoryRepository histories;
    private final PayoutMovementRepository movements;
    private final PayoutProperties properties;
    private final PayoutGateway gateway;
    private final OutboxWriter outbox;
    private final EventEnvelopeFactory envelopes;
    private final Tracer tracer;

    /** Tạo payout cùng transaction xử lý completion; số tiền và tỷ lệ phí được chốt tại đây. */
    @Transactional
    public void createEligible(UUID orderId) {
        var eligible = payouts.findEligibleOrder(orderId);
        if (eligible.isEmpty()) return;
        var order = eligible.get();
        BigDecimal amount = (BigDecimal) order.get("amount");
        BigDecimal declared = (BigDecimal) order.get("order_amount");
        BigDecimal shipping = (BigDecimal) order.get("shipping_fee");
        BigDecimal rate = properties.getPlatformFeeRate();
        BigDecimal base = amount.subtract(shipping);
        BigDecimal fee = base.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(declared) != 0 || shipping.signum() < 0 || base.subtract(fee).signum() <= 0) {
            log.warn("Payout not eligible: orderId={} reason=invalid_or_mismatched_amount", orderId);
            return;
        }

        // Đồng bộ với thao tác verify để payout không bị giữ sau khi tài khoản đã được duyệt.
        UUID sellerId = (UUID) order.get("seller_id");
        payouts.lockSellerBank(sellerId);
        UUID bankId = payouts.findVerifiedDefaultBank(sellerId).orElse(null);
        String status = bankId == null ? "ON_HOLD" : "PENDING";
        UUID id = payouts.create(orderId, order, bankId, status,
                gateway.provider(), rate, fee, base.subtract(fee));
        if (id == null) return;
        var payout = locked(id);
        history(payout, "CREATED", null, str(payout, "status"), SYSTEM, null, null, null, null);
        if (bankId == null) history(payout, "HELD", "ON_HOLD", "ON_HOLD", SYSTEM, null, null, null, null);
    }

    @EventListener
    @Transactional
    public void verified(BankAccountVerified event) {
        releaseHeld(event.sellerId());
    }

    /** Giải phóng các payout đang giữ khi seller có tài khoản mặc định đã xác minh. */
    @Transactional
    public void releaseHeld(UUID sellerId) {
        payouts.lockSellerBank(sellerId);
        var bankId = payouts.findVerifiedDefaultBank(sellerId);
        if (bankId.isEmpty()) return;
        for (var payout : payouts.findHeldForUpdate(sellerId)) {
            UUID id = (UUID) payout.get("id");
            payouts.release(id, bankId.get());
            history(locked(id), "RELEASED", "ON_HOLD", "PENDING", SYSTEM, null, null, null, null);
        }
    }

    /** Yêu cầu retry lặp lại cùng key chỉ xác nhận thao tác cũ, không tạo thêm lần gửi. */
    @Transactional
    public void retry(CurrentUser actor, UUID id, String requestKey) {
        if (actor == null || !actor.hasRole("ADMIN")) throw new DomainException(ACCESS_DENIED);
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9._:-]{1,100}"))
            throw new DomainException(PAYOUT_IDEMPOTENCY_INVALID);
        payouts.lockRetryRequest(requestKey);
        var replay = histories.findRetryRequest(requestKey);
        if (replay.isPresent()) {
            if (!id.equals(replay.get().get("payout_id")) || !actor.id().equals(replay.get().get("actor_id")))
                throw new DomainException(PAYOUT_IDEMPOTENCY_INVALID);
            return;
        }

        var payout = locked(id);
        String from = str(payout, "status");
        if (!Set.of("FAILED", "RETURNED").contains(from)) throw new DomainException(PAYOUT_NOT_RETRYABLE);
        if (payouts.findVerifiedDefaultBank((UUID) payout.get("seller_id")).isEmpty())
            throw new DomainException(PAYOUT_NOT_RETRYABLE, "A verified active default bank account is required");
        payouts.scheduleAdminRetry(id, actor.id());
        history(locked(id), "RETRIED", from, "PENDING", actor.id(), null, null, null, requestKey);
    }

    /** Chỉ áp dụng kết quả đúng attempt; giao dịch tiền chỉ được ghi khi provider xác nhận. */
    @Transactional
    public void apply(UUID id, int attempt, String provider, PayoutGateway.Result result, boolean reconciled) {
        var payout = locked(id);
        if (attempt != integer(payout, "attempt_count") || !Objects.equals(provider, payout.get("provider"))) return;
        String from = str(payout, "status");
        if (!Set.of("PROCESSING", "UNKNOWN", "SUCCEEDED").contains(from)) return;
        if (result == null || result.state() == null)
            result = new PayoutGateway.Result(PayoutGateway.State.UNKNOWN, null, "INVALID_RESPONSE");
        if (result.reference() != null && (result.reference().isBlank() || result.reference().length() > 120))
            result = new PayoutGateway.Result(PayoutGateway.State.UNKNOWN, null, "INVALID_REFERENCE");
        if ((result.state() == PayoutGateway.State.SUCCEEDED || result.state() == PayoutGateway.State.RETURNED)
                && result.reference() == null)
            result = new PayoutGateway.Result(PayoutGateway.State.UNKNOWN, null, "MISSING_REFERENCE");
        if ("SUCCEEDED".equals(from) && result.state() != PayoutGateway.State.RETURNED) return;
        if (result.reference() != null && payout.get("provider_reference") != null
                && !result.reference().equals(payout.get("provider_reference")))
            throw new DomainException(PAYOUT_PROVIDER_CONFLICT);
        if (result.reference() != null) payouts.updateProviderReference(id, result.reference());

        String to = switch (result.state()) {
            case ACCEPTED -> "PROCESSING";
            case UNKNOWN, NOT_FOUND -> "UNKNOWN";
            case FAILED -> "FAILED";
            case SUCCEEDED -> "SUCCEEDED";
            case RETURNED -> "RETURNED";
        };
        if (from.equals(to) && !reconciled) return;
        UUID transaction = null;
        if (result.state() == PayoutGateway.State.SUCCEEDED || result.state() == PayoutGateway.State.RETURNED) {
            if (payout.get("transaction_id") == null || !"SUCCEEDED".equals(from)) {
                transaction = movement(payout, "PAYOUT", result.reference());
                payouts.recordSuccess(id, transaction);
                if (result.state() == PayoutGateway.State.RETURNED) {
                    history(locked(id), "SUCCEEDED", from, "SUCCEEDED", SYSTEM, result, null, transaction, null);
                    publish(locked(id), "PayoutSucceeded");
                    from = "SUCCEEDED";
                }
            }
            if (result.state() == PayoutGateway.State.RETURNED) {
                String returnReference = "return-" + UUID.nameUUIDFromBytes(
                        (provider + ":" + result.reference()).getBytes(StandardCharsets.UTF_8));
                transaction = movement(payout, "PAYOUT_RETURN", returnReference);
                payouts.recordReturn(id, transaction);
            }
        }

        OffsetDateTime nextAttemptAt = "SUCCEEDED".equals(to) && gateway.simulated()
                && "RETURN".equals(properties.getSandboxOutcome())
                ? OffsetDateTime.now(ZoneOffset.UTC).plus(properties.getReconcileInterval()) : null;
        payouts.updateOutcome(id, to, SYSTEM, nextAttemptAt);
        var updated = locked(id);
        String event = switch (result.state()) {
            case ACCEPTED -> "RECONCILED";
            case UNKNOWN, NOT_FOUND -> "TIMED_OUT";
            default -> to;
        };
        history(updated, event, from, to, SYSTEM, result,
                "FAILED".equals(to) ? "PROVIDER_FAILED" : null, transaction, null);
        if (reconciled && !event.equals("RECONCILED"))
            history(updated, "RECONCILED", to, to, SYSTEM, result, null, transaction, null);
        if ("SUCCEEDED".equals(to)) publish(updated, "PayoutSucceeded");
        if ("FAILED".equals(to)) {
            if (attempt < properties.getMaxAttempts()) {
                payouts.scheduleAutomaticRetry(id,
                        OffsetDateTime.now(ZoneOffset.UTC).plus(properties.getRetryBackoff().multipliedBy(attempt)));
                history(locked(id), "RETRIED", "FAILED", "PENDING", SYSTEM, result, null, null, null);
            } else {
                publish(updated, "PayoutFailed");
            }
        }
    }

    Map<String, Object> locked(UUID id) {
        return payouts.findLocked(id).orElseThrow(() -> new DomainException(PAYOUT_NOT_FOUND));
    }

    /** Bên gọi phải giữ khóa payout để event_seq và snapshot cùng phản ánh một lần chuyển trạng thái. */
    void history(Map<String, Object> payout, String event, String from, String to, UUID actor,
            PayoutGateway.Result result, String error, UUID transaction, String requestKey) {
        String providerKey = integer(payout, "attempt_count") == 0 ? null
                : PayoutAttemptKey.of((UUID) payout.get("id"), integer(payout, "attempt_count"));
        histories.append(payout, new PayoutHistoryRepository.Change(event, from, to, actor,
                providerKey, result == null ? null : sanitizeStatus(result.providerStatus()),
                error, error == null ? null : "Provider reported transfer failure", transaction, requestKey));
    }

    private UUID movement(Map<String, Object> payout, String type, String reference) {
        String movementKey = ("PAYOUT".equals(type) ? "payout:" : "payout-return:")
                + payout.get("id") + ":" + payout.get("attempt_count");
        return movements.findByIdempotencyKey(movementKey)
                .orElseGet(() -> movements.recordMovement(payout, type, movementKey, reference));
    }

    private void publish(Map<String, Object> payout, String event) {
        var data = new LinkedHashMap<String, Object>();
        data.put("payoutId", payout.get("id"));
        data.put("orderId", payout.get("order_id"));
        data.put("sellerId", payout.get("seller_id"));
        data.put("netAmount", payout.get("net_amount").toString());
        data.put("currency", payout.get("currency"));
        data.put("attempt", payout.get("attempt_count"));
        data.put("simulated", "sandbox".equals(payout.get("provider")));
        if (tracer.currentSpan() != null) {
            outbox.write("Payout", Topics.PAYMENT, envelopes.create(event, payout.get("id").toString(), data));
        } else {
            var span = tracer.nextSpan().name("payout-transition").start();
            try (var scope = tracer.withSpan(span)) {
                outbox.write("Payout", Topics.PAYMENT, envelopes.create(event, payout.get("id").toString(), data));
            } finally {
                span.end();
            }
        }
    }

    static int integer(Map<String, Object> payout, String key) {
        return ((Number) payout.get(key)).intValue();
    }

    static String str(Map<String, Object> payout, String key) {
        return Objects.toString(payout.get(key), null);
    }

    private String sanitizeStatus(String value) {
        // Không lưu thông báo tự do từ provider vì có thể chứa số tài khoản hoặc thông tin xác thực.
        return value != null && value.matches("[A-Z_]{1,80}") ? value : "PROVIDER_RESPONSE";
    }
}
