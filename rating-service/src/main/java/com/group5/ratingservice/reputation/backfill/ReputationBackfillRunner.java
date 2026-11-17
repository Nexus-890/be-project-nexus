package com.group5.ratingservice.reputation.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nexus.reputation.backfill.enabled", havingValue = "true")
public class ReputationBackfillRunner implements ApplicationRunner {
    private final ReputationBackfill backfill;
    @Value("${nexus.reputation.backfill.dry-run:true}") private boolean dryRun;
    @Value("${nexus.reputation.backfill.batch-size:100}") private int batchSize;

    @Override
    public void run(ApplicationArguments arguments) {
        UUID completion = cursor(arguments, "reputation-completion-cursor");
        UUID rating = cursor(arguments, "reputation-rating-cursor");
        ReputationBackfill.Report report;
        do {
            report = backfill.batch(completion, rating, batchSize, dryRun);
            log.info("REPUTATION_BACKFILL_BATCH {}", report);
            completion = report.completionCursor();
            rating = report.ratingCursor();
        } while (report.moreCompletions() || report.moreRatings());
    }

    private static UUID cursor(ApplicationArguments arguments, String name) {
        if (!arguments.containsOption(name)) return null;
        var values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1) throw new IllegalArgumentException("One UUID cursor is required: " + name);
        return UUID.fromString(values.getFirst());
    }
}
