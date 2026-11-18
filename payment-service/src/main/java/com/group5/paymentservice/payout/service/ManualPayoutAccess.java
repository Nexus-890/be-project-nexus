package com.group5.paymentservice.payout.service;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.*;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.config.ManualPayoutProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * One policy used by commands, queries and binary routes. Role never bypasses executor ownership.
 */
@Component
@RequiredArgsConstructor
public class ManualPayoutAccess {
    private final ManualPayoutProperties settings;

    public void identity(CurrentUser actor) {
        if (actor == null || actor.id() == null) throw new DomainException(UNAUTHENTICATED);
    }

    public void staff(CurrentUser actor) {
        identity(actor);
        if (!actor.hasRole("ADMIN") && !actor.hasRole("SUPPORT_STAFF"))
            throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
    }

    public void admin(CurrentUser actor) {
        staff(actor);
        if (!actor.hasRole("ADMIN")) throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
    }

    public void seller(CurrentUser actor, Map<String, Object> payout) {
        identity(actor);
        if (!actor.hasRole("SELLER")) throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
        if (!actor.id().equals(payout.get("seller_id")))
            throw new DomainException(MANUAL_PAYOUT_NOT_FOUND);
    }

    public void noSelfPayment(CurrentUser actor, Map<String, Object> payout) {
        if (actor.id().equals(payout.get("seller_id")))
            throw new DomainException(MANUAL_PAYOUT_FORBIDDEN);
    }

    public void owner(CurrentUser actor, Map<String, Object> attempt) {
        if (!actor.id().equals(attempt.get("claimed_by")))
            throw new DomainException(MANUAL_PAYOUT_CONFLICT);
    }

    public List<String> actions(
            CurrentUser actor,
            Map<String, Object> p,
            Map<String, Object> a,
            boolean openCase,
            boolean openSellerDispute) {
        if (actor.id().equals(p.get("seller_id"))) return List.of();
        List<String> result = new ArrayList<>();
        String status = Objects.toString(p.get("status"));
        if (settings.isEnabled() && "PENDING".equals(status)) result.add("CLAIM");
        if ("ON_HOLD".equals(status)) result.add("READINESS_CHECK");
        if (actor.hasRole("ADMIN") && Set.of("FAILED", "RETURNED").contains(status) && !openCase)
            result.add("RETRY");
        if (a == null) return List.copyOf(result);
        boolean owner = actor.id().equals(a.get("claimed_by"));
        String state = Objects.toString(a.get("state"));
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (owner && "CLAIMED".equals(state)) {
            result.add("RELEASE");
            if (settings.isEnabled()
                    && ManualPayoutQueryService.time(a, "claim_expires_at").isAfter(now))
                result.add("ISSUE_QR");
        }
        if (!owner && actor.hasRole("ADMIN") && "CLAIMED".equals(state)) result.add("RELEASE");
        if (owner
                && "QR_ISSUED".equals(state)
                && ManualPayoutQueryService.time(a, "qr_expires_at").isAfter(now)) {
            result.add("READ_QR");
            result.add("CONFIRM_TRANSFER");
        }
        if (owner && Set.of("QR_ISSUED", "UNKNOWN", "SUCCEEDED").contains(state)) {
            result.add("UPLOAD_EVIDENCE");
            result.add("REPORT_PROBLEM");
        }
        if (actor.hasRole("ADMIN") && openCase) {
            result.add("RESOLVE");
            if (openSellerDispute) result.add("REVIEW_DISPUTE");
            if (!result.contains("UPLOAD_EVIDENCE")) result.add("UPLOAD_EVIDENCE");
        }
        return List.copyOf(result);
    }
}
