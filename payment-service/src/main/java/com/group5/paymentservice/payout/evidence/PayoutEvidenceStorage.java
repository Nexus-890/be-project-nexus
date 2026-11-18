package com.group5.paymentservice.payout.evidence;

import java.time.Instant;
import java.util.List;

public interface PayoutEvidenceStorage {
    boolean configured();

    void put(String objectKey, byte[] content, String contentType);

    StoredEvidence get(String objectKey);

    void delete(String objectKey);

    List<StoredObject> list(String prefix, int offset, int limit);

    record StoredEvidence(byte[] content, String contentType) {}

    record StoredObject(String name, boolean directory, Instant createdAt) {}
}
