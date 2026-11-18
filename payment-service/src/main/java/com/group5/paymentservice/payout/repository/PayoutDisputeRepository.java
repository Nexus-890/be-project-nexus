package com.group5.paymentservice.payout.repository;

import java.util.*;

public interface PayoutDisputeRepository {
    List<Map<String, Object>> cases(UUID payoutId);

    Optional<Map<String, Object>> reviewCase(UUID payoutId, UUID attemptId, UUID caseId);

    void insertCase(Map<String, Object> row);

    void updateCase(UUID id, Map<String, Object> fields);
}
