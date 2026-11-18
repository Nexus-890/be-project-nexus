package com.group5.paymentservice.payout.evidence;

import com.group5.paymentservice.payout.config.ManualPayoutProperties;
import com.group5.paymentservice.payout.repository.ManualPayoutRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Re-discovers orphan objects even if upload compensation was lost during a process crash. */
@Service
public class EvidenceOrphanCleanupService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES_PER_RUN = 100;
    private static final int MAX_DELETIONS_PER_RUN = 100;
    private static final String UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern FOLDER = Pattern.compile(UUID);
    private static final Pattern FILE = Pattern.compile(UUID + "\\.(jpg|png|pdf)");
    private final PayoutEvidenceStorage storage;
    private final ManualPayoutRepository references;
    private final ManualPayoutProperties settings;
    private final Clock clock;
    private final Deque<Scan> scans = new ArrayDeque<>();
    private String scannedBucket;

    @Autowired
    public EvidenceOrphanCleanupService(
            PayoutEvidenceStorage storage,
            ManualPayoutRepository references,
            ManualPayoutProperties settings) {
        this(storage, references, settings, Clock.systemUTC());
    }

    EvidenceOrphanCleanupService(
            PayoutEvidenceStorage storage,
            ManualPayoutRepository references,
            ManualPayoutProperties settings,
            Clock clock) {
        this.storage = storage;
        this.references = references;
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized int cleanup() {
        if (!storage.configured()) return 0;
        String bucket = settings.getEvidence().getBucket();
        if (!bucket.equals(scannedBucket)) {
            scans.clear();
            scannedBucket = bucket;
        }
        if (scans.isEmpty()) scans.add(new Scan("", 0));
        Instant cutoff = clock.instant().minus(settings.getEvidence().getOrphanGrace());
        int deleted = 0;
        for (int pages = 0; pages < MAX_PAGES_PER_RUN && !scans.isEmpty(); pages++) {
            Scan scan = scans.removeFirst();
            try {
                var entries = storage.list(scan.prefix(), scan.offset(), PAGE_SIZE);
                int depth = scan.prefix().isEmpty() ? 0 : scan.prefix().split("/").length;
                int survivors = 0;
                for (int index = 0; index < entries.size(); index++) {
                    var entry = entries.get(index);
                    String key =
                            scan.prefix().isEmpty()
                                    ? entry.name()
                                    : scan.prefix() + "/" + entry.name();
                    if (entry.directory() && depth < 2 && FOLDER.matcher(entry.name()).matches()) {
                        scans.addLast(new Scan(key, 0));
                    }
                    boolean removable =
                            depth == 2
                                    && !entry.directory()
                                    && FILE.matcher(entry.name()).matches()
                                    && entry.createdAt() != null
                                    && entry.createdAt().isBefore(cutoff);
                    if (removable && !references.evidenceObjectExists(key)) {
                        storage.delete(key);
                        deleted++;
                    } else {
                        survivors++;
                    }
                    if (deleted == MAX_DELETIONS_PER_RUN) {
                        // Offset accounts only for surviving rows after the current page shrank.
                        scans.addFirst(new Scan(scan.prefix(), scan.offset() + survivors));
                        return deleted;
                    }
                }
                if (entries.size() == PAGE_SIZE) {
                    // Complete parent listings before processing/deleting descendant objects.
                    scans.addFirst(new Scan(scan.prefix(), scan.offset() + survivors));
                }
            } catch (RuntimeException error) {
                scans.addFirst(scan);
                throw error;
            }
        }
        return deleted;
    }

    private record Scan(String prefix, int offset) {}
}
