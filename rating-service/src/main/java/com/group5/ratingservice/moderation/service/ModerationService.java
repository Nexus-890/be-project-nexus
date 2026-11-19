package com.group5.ratingservice.moderation.service;

import com.group5.common_core.exception.DomainException;
import com.group5.ratingservice.eligibility.enums.EligibilityAction;
import com.group5.ratingservice.moderation.dto.*;
import com.group5.ratingservice.reputation.service.ReputationService;
import com.group5.ratingservice.shared.exception.RatingErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModerationService {
    private final JdbcTemplate jdbc;
    private final ReputationService reputations;
    private static final String RESTRICTIONS = "SELECT *, revoked_at IS NULL AND restricted_until>clock_timestamp() AS active FROM reputation_restrictions ";
    private static final String DISPUTES = """
            SELECT d.*, r.score AS rating_score, r.comment AS rating_comment
            FROM rating_disputes d JOIN ratings r ON r.id=d.rating_id
            """;

    @Transactional(readOnly = true)
    public Page<RestrictionResponse> restrictions(UUID userId, int page, int size) {
        checkPage(page, size);
        var rows = jdbc.query(RESTRICTIONS + "WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                RESTRICTION, userId, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM reputation_restrictions WHERE user_id=?", Long.class, userId);
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    @Transactional
    public RestrictionResponse restrict(UUID userId, RestrictionRequest request, UUID actor) {
        String reason = reason(request.reason());
        if (request.action() == null || request.restrictedUntil() == null
                || !request.restrictedUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO reputation_restrictions(user_id,action,reason,restricted_until,created_by)
                VALUES (?,?,?,?,?) RETURNING id
                """, UUID.class, userId, request.action().name(), reason, request.restrictedUntil(), actor);
        audit("RESTRICTION_APPLIED", actor, userId, id, reason);
        return restriction(userId, id, false);
    }

    @Transactional
    public RestrictionResponse revoke(UUID userId, UUID id, String reason, UUID actor) {
        String normalized = reason(reason);
        RestrictionResponse restriction = restriction(userId, id, true);
        if (restriction.revokedAt() != null) {
            if (!actor.equals(restriction.revokedBy()) || !normalized.equals(restriction.revocationReason())) {
                throw new DomainException(RatingErrorCode.CONFLICT, "The restriction has already been revoked");
            }
            return restriction;
        }
        jdbc.update("""
                UPDATE reputation_restrictions SET revoked_at=clock_timestamp(),revoked_by=?,revocation_reason=?
                WHERE id=?
                """, actor, normalized, id);
        audit("RESTRICTION_REVOKED", actor, userId, id, normalized);
        return restriction(userId, id, false);
    }

    @Transactional(readOnly = true)
    public OffsetDateTime restrictedUntil(UUID userId, EligibilityAction action) {
        return jdbc.queryForObject("""
                SELECT max(restricted_until) FROM reputation_restrictions
                WHERE user_id=? AND action=? AND revoked_at IS NULL AND restricted_until>clock_timestamp()
                """, OffsetDateTime.class, userId, action.name());
    }

    @Transactional
    public DisputeResponse dispute(UUID ratingId, String reason, UUID actor) {
        String normalized = reason(reason);
        var ratings = jdbc.queryForList("SELECT to_user_id,hidden FROM ratings WHERE id=?", ratingId);
        if (ratings.isEmpty()) throw new DomainException(RatingErrorCode.NOT_FOUND);
        var rating = ratings.getFirst();
        if (!actor.equals(rating.get("to_user_id"))) throw new DomainException(RatingErrorCode.FORBIDDEN);
        if (Boolean.TRUE.equals(rating.get("hidden"))) throw new DomainException(RatingErrorCode.CONFLICT);
        List<UUID> ids = jdbc.query("""
                INSERT INTO rating_disputes(rating_id,user_id,reason) VALUES (?,?,?)
                ON CONFLICT (rating_id) DO NOTHING RETURNING id
                """, (row, index) -> row.getObject("id", UUID.class), ratingId, actor, normalized);
        if (ids.isEmpty()) throw new DomainException(RatingErrorCode.CONFLICT, "This rating already has a dispute");
        UUID id = ids.getFirst();
        audit("DISPUTE_SUBMITTED", actor, actor, id, normalized);
        return dispute(id, false);
    }

    @Transactional(readOnly = true)
    public Page<DisputeResponse> disputes(DisputeStatus status, int page, int size) {
        checkPage(page, size);
        String selected = status == null ? null : status.name();
        var rows = jdbc.query(DISPUTES + " WHERE (?::varchar IS NULL OR d.status=?) ORDER BY d.created_at DESC,d.id DESC LIMIT ? OFFSET ?",
                DISPUTE, selected, selected, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) FROM rating_disputes WHERE (?::varchar IS NULL OR status=?)",
                Long.class, selected, selected);
        return new PageImpl<>(rows, PageRequest.of(page, size), total);
    }

    @Transactional
    public DisputeResponse resolve(UUID id, DisputeStatus status, String reason, UUID actor) {
        String normalized = reason(reason);
        if (status == null || status == DisputeStatus.PENDING) throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        DisputeResponse dispute = dispute(id, true);
        if (dispute.status() != DisputeStatus.PENDING) {
            if (status != dispute.status() || !normalized.equals(dispute.resolutionReason()) || !actor.equals(dispute.resolvedBy())) {
                throw new DomainException(RatingErrorCode.CONFLICT, "The dispute has already been resolved");
            }
            return dispute;
        }
        if (status == DisputeStatus.APPROVED) {
            reputations.removeDisputedRating(dispute.ratingId(), dispute.userId(), id, actor, normalized);
        }
        jdbc.update("""
                UPDATE rating_disputes SET status=?,resolved_at=clock_timestamp(),resolved_by=?,resolution_reason=? WHERE id=?
                """, status.name(), actor, normalized, id);
        audit("DISPUTE_" + status.name(), actor, dispute.userId(), id, normalized);
        return dispute(id, false);
    }

    private RestrictionResponse restriction(UUID userId, UUID id, boolean lock) {
        return jdbc.query(RESTRICTIONS + "WHERE user_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), RESTRICTION, userId, id)
                .stream().findFirst().orElseThrow(() -> new DomainException(RatingErrorCode.NOT_FOUND));
    }

    private DisputeResponse dispute(UUID id, boolean lock) {
        return jdbc.query(DISPUTES + " WHERE d.id=?" + (lock ? " FOR UPDATE OF d" : ""), DISPUTE, id)
                .stream().findFirst().orElseThrow(() -> new DomainException(RatingErrorCode.NOT_FOUND));
    }

    private void audit(String action, UUID actor, UUID user, UUID resource, String reason) {
        jdbc.update("INSERT INTO reputation_moderation_audit(action,actor_id,user_id,resource_id,reason) VALUES (?,?,?,?,?)",
                action, actor, user, resource, reason);
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        return reason.strip();
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new DomainException(RatingErrorCode.INVALID_PARAMETER);
        }
    }

    private static final RowMapper<RestrictionResponse> RESTRICTION = (row, index) -> new RestrictionResponse(
            row.getObject("id", UUID.class), row.getObject("user_id", UUID.class), EligibilityAction.valueOf(row.getString("action")),
            row.getString("reason"), row.getObject("restricted_until", OffsetDateTime.class), row.getObject("created_at", OffsetDateTime.class),
            row.getObject("created_by", UUID.class), row.getObject("revoked_at", OffsetDateTime.class), row.getObject("revoked_by", UUID.class),
            row.getString("revocation_reason"), row.getBoolean("active"));

    private static final RowMapper<DisputeResponse> DISPUTE = (row, index) -> new DisputeResponse(
            row.getObject("id", UUID.class), row.getObject("rating_id", UUID.class), row.getObject("user_id", UUID.class),
            row.getString("reason"), DisputeStatus.valueOf(row.getString("status")), row.getObject("created_at", OffsetDateTime.class),
            row.getObject("resolved_at", OffsetDateTime.class), row.getObject("resolved_by", UUID.class), row.getString("resolution_reason"),
            row.getInt("rating_score"), row.getString("rating_comment"));
}
