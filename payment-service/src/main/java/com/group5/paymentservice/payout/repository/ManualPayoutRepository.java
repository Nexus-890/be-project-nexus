package com.group5.paymentservice.payout.repository;

import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.ManualPayoutFilter;

import java.util.*;

/** Persistence boundary only; services own authorization, transitions and transactions. */
public interface ManualPayoutRepository {
    void lockKey(String key);

    Optional<Map<String, Object>> payout(UUID id, boolean locked);

    void lockPayoutContext(Map<String, Object> payout);

    Optional<Map<String, Object>> attempt(UUID payoutId, UUID attemptId);

    Optional<Map<String, Object>> currentAttempt(UUID payoutId);

    Optional<Map<String, Object>> bank(UUID sellerId);

    boolean funded(UUID payoutId);

    void insertAttempt(Map<String, Object> row);

    void updateAttempt(UUID id, Map<String, Object> fields);

    void updatePayout(UUID id, Map<String, Object> fields);

    Optional<Map<String, Object>> command(UUID key);

    void recordCommand(
            UUID key,
            UUID actor,
            String operation,
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String hash,
            String json,
            String actorRole);

    List<UUID> queue(ManualPayoutFilter filter, CurrentUser actor);

    long count(ManualPayoutFilter filter, CurrentUser actor);

    List<Map<String, Object>> history(UUID payoutId);

    void audit(
            UUID payoutId,
            UUID attemptId,
            UUID caseId,
            String event,
            String from,
            UUID actor,
            String role,
            String reason);

    List<Map<String, Object>> evidence(UUID attemptId);

    Optional<Map<String, Object>> evidence(UUID payoutId, UUID attemptId, UUID evidenceId);

    void insertEvidence(Map<String, Object> row);

    void attachEvidence(UUID targetId, boolean reviewCase, List<UUID> evidenceIds);

    boolean evidenceObjectExists(String key);

    UUID movement(UUID payoutId, UUID attemptId, String type, UUID original);

    List<UUID> expiring(UUID after, int limit);

    List<UUID> issuedBySeller(UUID sellerId);
}
