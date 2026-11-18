package com.group5.paymentservice.payout.service;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.*;
import com.group5.paymentservice.payout.dto.request.ManualPayoutFilter;
import com.group5.paymentservice.payout.dto.response.*;
import com.group5.paymentservice.payout.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManualPayoutQueryService {
    private final ManualPayoutRepository repository;
    private final PayoutDisputeRepository disputes;
    private final ManualPayoutAccess access;
    private final ManualPayoutReadinessService readiness;
    private final ManualPayoutProperties settings;
    private final PayoutProperties payoutSettings;

    public ManualPayoutCapabilities capabilities(CurrentUser actor) {
        access.staff(actor);
        return new ManualPayoutCapabilities(
                settings.isEnabled(),
                payoutSettings.getExecutionMode(),
                payoutSettings.isJobsEnabled(),
                now(),
                settings.getSourceAccounts(),
                actor.hasRole("ADMIN") ? List.of("EXECUTE", "RECONCILE") : List.of("EXECUTE"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ManualPayoutPage queue(CurrentUser actor, ManualPayoutFilter filter) {
        access.staff(actor);
        if (filter.page() < 0
                || filter.size() < 1
                || filter.size() > 100
                || (filter.from() != null
                        && filter.to() != null
                        && filter.from().isAfter(filter.to())))
            throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
        if (filter.claimedBy() != null && !"me".equals(filter.claimedBy()))
            try {
                UUID.fromString(filter.claimedBy());
            } catch (IllegalArgumentException e) {
                throw new DomainException(MANUAL_PAYOUT_INVALID_REQUEST);
            }
        long total = repository.count(filter, actor);
        return new ManualPayoutPage(
                repository.queue(filter, actor).stream().map(id -> view(actor, id, false)).toList(),
                new PageInfo(
                        filter.page(),
                        filter.size(),
                        total,
                        (int) ((total + filter.size() - 1) / filter.size())));
    }

    public ManualPayoutView detail(CurrentUser actor, UUID payoutId) {
        access.staff(actor);
        return view(actor, payoutId, false);
    }

    ManualPayoutView view(CurrentUser actor, UUID payoutId, boolean sellerContext) {
        var p =
                repository
                        .payout(payoutId, false)
                        .filter(row -> "MANUAL".equals(row.get("execution_mode")))
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        var a = repository.currentAttempt(payoutId).orElse(null);
        var cases = disputes.cases(payoutId);
        boolean open = cases.stream().anyMatch(c -> !"RESOLVED".equals(c.get("status")));
        var attempt =
                a == null
                        ? null
                        : new ManualPayoutView.Attempt(
                                id(a, "id"),
                                number(a, "attempt_no").intValue(),
                                str(a, "state"),
                                number(a, "version").longValue(),
                                id(a, "claimed_by"),
                                time(a, "claim_expires_at"),
                                time(a, "qr_expires_at"),
                                str(a, "reference"),
                                str(a, "receipt_status"),
                                number(a, "receipt_version").longValue(),
                                time(a, "seller_confirmed_at"));
        boolean privateAccess =
                !sellerContext
                        && a != null
                        && (actor.id().equals(a.get("claimed_by"))
                                || (actor.hasRole("ADMIN")
                                        && cases.stream()
                                                .anyMatch(
                                                        c ->
                                                                a.get("id")
                                                                        .equals(
                                                                                c.get(
                                                                                        "attempt_id")))
                                        && !actor.id().equals(p.get("seller_id"))));
        var evidence =
                privateAccess
                        ? repository.evidence(id(a, "id")).stream()
                                .map(
                                        e ->
                                                new ManualPayoutView.Evidence(
                                                        id(e, "id"),
                                                        str(e, "content_type"),
                                                        number(e, "size_bytes").longValue(),
                                                        time(e, "created_at")))
                                .toList()
                        : List.<ManualPayoutView.Evidence>of();
        var visibleCases =
                cases.stream()
                        .filter(
                                c ->
                                        !sellerContext
                                                || a != null
                                                        && a.get("id").equals(c.get("attempt_id"))
                                                        && "SELLER_DISPUTE".equals(c.get("kind")))
                        .map(
                                c ->
                                        new ManualPayoutView.ReviewCase(
                                                id(c, "id"),
                                                str(c, "kind"),
                                                str(c, "status"),
                                                str(c, "reason"),
                                                sellerContext
                                                                || actor.id()
                                                                        .equals(
                                                                                c.get(
                                                                                        "reported_by"))
                                                                || reviewDetails(
                                                                        actor,
                                                                        p,
                                                                        a,
                                                                        id(c, "attempt_id"))
                                                        ? str(c, "explanation")
                                                        : "Review details are restricted",
                                                number(c, "version").longValue(),
                                                time(c, "created_at")))
                        .toList();
        var history =
                sellerContext
                        ? List.<ManualPayoutView.History>of()
                        : repository.history(payoutId).stream()
                                .map(
                                        h ->
                                                new ManualPayoutView.History(
                                                        id(h, "id"),
                                                        str(h, "event"),
                                                        id(h, "manual_attempt_id"),
                                                        id(h, "case_id"),
                                                        id(h, "actor_id"),
                                                        str(h, "actor_role"),
                                                        actor.id().equals(h.get("actor_id"))
                                                                        || reviewDetails(
                                                                                actor,
                                                                                p,
                                                                                a,
                                                                                id(
                                                                                        h,
                                                                                        "manual_attempt_id"))
                                                                ? str(h, "reason")
                                                                : null,
                                                        time(h, "occurred_at")))
                                .toList();
        var bank = a == null ? repository.bank(id(p, "seller_id")).orElse(null) : null;
        var recipient =
                a != null
                        ? new ManualPayoutView.Recipient(
                                str(a, "bank_code"), str(a, "account_last4"), str(a, "holder_name"))
                        : bank == null
                                ? null
                                : new ManualPayoutView.Recipient(
                                        str(bank, "bank_code"),
                                        str(bank, "account_last4"),
                                        str(bank, "account_holder_name"));
        var allowed =
                new ArrayList<>(
                        sellerContext ? List.<String>of() : access.actions(actor, p, a, open));
        String blockedReason = str(p, "blocked_reason");
        if (Set.of("PENDING", "ON_HOLD").contains(str(p, "status"))) {
            blockedReason = readiness.blockedReason(p);
            if (blockedReason != null) {
                allowed.remove("CLAIM");
                if (!sellerContext
                        && !actor.id().equals(p.get("seller_id"))
                        && !allowed.contains("READINESS_CHECK")) allowed.add("READINESS_CHECK");
            }
        }
        if (cases.stream()
                .noneMatch(
                        c ->
                                "SELLER_DISPUTE".equals(c.get("kind"))
                                        && !"RESOLVED".equals(c.get("status"))))
            allowed.remove("REVIEW_DISPUTE");
        return new ManualPayoutView(
                payoutId,
                id(p, "order_id"),
                id(p, "seller_id"),
                str(p, "status"),
                str(p, "execution_mode"),
                str(p, "confirmation_source"),
                money(p, "order_amount"),
                money(p, "shipping_fee"),
                money(p, "platform_fee"),
                money(p, "net_amount"),
                str(p, "currency"),
                number(p, "version").longValue(),
                blockedReason,
                List.copyOf(allowed),
                now(),
                time(p, "created_at"),
                time(p, "updated_at"),
                attempt,
                evidence,
                visibleCases,
                history,
                recipient);
    }

    public ManualReceiptView sellerProjection(CurrentUser actor, UUID payoutId) {
        var p =
                repository
                        .payout(payoutId, false)
                        .orElseThrow(() -> new DomainException(MANUAL_PAYOUT_NOT_FOUND));
        access.seller(actor, p);
        if (!"MANUAL".equals(p.get("execution_mode"))) return null;
        var a = repository.currentAttempt(payoutId).orElse(null);
        if (a == null) return null;
        var sellerCases =
                disputes.cases(payoutId).stream()
                        .filter(
                                c ->
                                        a.get("id").equals(c.get("attempt_id"))
                                                && "SELLER_DISPUTE".equals(c.get("kind")))
                        .toList();
        boolean open = sellerCases.stream().anyMatch(c -> !"RESOLVED".equals(c.get("status")));
        var review =
                sellerCases.stream()
                        .filter(c -> !"RESOLVED".equals(c.get("status")))
                        .findFirst()
                        .or(() -> sellerCases.stream().findFirst())
                        .orElse(null);
        List<String> actions = new ArrayList<>();
        if ("SUCCEEDED".equals(p.get("status")) && "SUCCEEDED".equals(a.get("state"))) {
            if (!"CONFIRMED".equals(a.get("receipt_status"))) actions.add("CONFIRM_RECEIPT");
            actions.add("OPEN_DISPUTE");
        }
        return new ManualReceiptView(
                id(a, "id"),
                str(a, "state"),
                str(a, "receipt_status"),
                number(a, "receipt_version").longValue(),
                open,
                List.copyOf(actions),
                review == null
                        ? null
                        : new ManualReceiptView.DisputeSummary(
                                id(review, "id"), str(review, "status"), str(review, "reason")));
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private boolean reviewDetails(
            CurrentUser actor,
            Map<String, Object> payout,
            Map<String, Object> attempt,
            UUID attemptId) {
        return !actor.id().equals(payout.get("seller_id"))
                && (actor.hasRole("ADMIN")
                        || attempt != null
                                && Objects.equals(attemptId, attempt.get("id"))
                                && actor.id().equals(attempt.get("claimed_by")));
    }

    static String str(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), null);
    }

    static UUID id(Map<String, Object> row, String key) {
        return (UUID) row.get(key);
    }

    static Number number(Map<String, Object> row, String key) {
        return (Number) row.get(key);
    }

    static OffsetDateTime time(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof java.sql.Timestamp t
                ? t.toInstant().atOffset(ZoneOffset.UTC)
                : (OffsetDateTime) v;
    }

    private static String money(Map<String, Object> row, String key) {
        return ((BigDecimal) row.get(key)).setScale(2).toPlainString();
    }
}
