package com.group5.ratingservice.shared;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationUpgradeTest {
    @Test
    void appliedV1DataSurvivesV2AndV3WithoutInventedReputationHistoryOrEvents() throws Exception {
        TestPostgres postgres = new TestPostgres();
        postgres.start();
        try {
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("1").load().migrate();
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            UUID order = UUID.randomUUID();
            UUID auction = UUID.randomUUID();
            UUID rating = UUID.randomUUID();
            UUID reputation = UUID.randomUUID();
            UUID completionEvent = UUID.randomUUID();
            OffsetDateTime completedAt = OffsetDateTime.parse("2026-10-31T00:00:00.123456Z");
            OffsetDateTime ratedAt = OffsetDateTime.parse("2026-11-01T00:00:00.654321Z");
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO rating_eligibility (order_id,buyer_id,seller_id,completed_at,auction_id,
                            source_event_id,source_version,created_by,updated_by) VALUES (?,?,?,?,?,?,7,?,?)
                        """)) {
                    statement.setObject(1, order);
                    statement.setObject(2, buyer);
                    statement.setObject(3, seller);
                    statement.setObject(4, completedAt);
                    statement.setObject(5, auction);
                    statement.setObject(6, completionEvent);
                    statement.setObject(7, actor);
                    statement.setObject(8, actor);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO ratings (id,from_user_id,to_user_id,order_id,auction_id,score,comment,
                            created_at,updated_at,created_by,updated_by) VALUES (?,?,?,?,?,4,'Original review',?,?,?,?)
                        """)) {
                    statement.setObject(1, rating);
                    statement.setObject(2, buyer);
                    statement.setObject(3, seller);
                    statement.setObject(4, order);
                    statement.setObject(5, auction);
                    statement.setObject(6, ratedAt);
                    statement.setObject(7, ratedAt);
                    statement.setObject(8, buyer);
                    statement.setObject(9, buyer);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO user_reputations (id,user_id,score,trust_level,total_transaction,
                            successful_transaction,failed_transaction,created_by,updated_by,version)
                        VALUES (?,?,73,'TRUSTED',12,10,2,?,?,9)
                        """)) {
                    statement.setObject(1, reputation);
                    statement.setObject(2, seller);
                    statement.setObject(3, actor);
                    statement.setObject(4, actor);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("INSERT INTO processed_events (event_id,consumer) VALUES (?,?)")) {
                    statement.setObject(1, completionEvent);
                    statement.setString(2, "rating-service.order-completed");
                    statement.executeUpdate();
                }
            }

            var result = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .load().migrate();
            assertThat(result.migrationsExecuted).isEqualTo(2);
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM ratings")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("id", UUID.class)).isEqualTo(rating);
                    assertThat(rows.getObject("from_user_id", UUID.class)).isEqualTo(buyer);
                    assertThat(rows.getObject("to_user_id", UUID.class)).isEqualTo(seller);
                    assertThat(rows.getObject("order_id", UUID.class)).isEqualTo(order);
                    assertThat(rows.getObject("auction_id", UUID.class)).isEqualTo(auction);
                    assertThat(rows.getInt("score")).isEqualTo(4);
                    assertThat(rows.getString("comment")).isEqualTo("Original review");
                    assertThat(rows.getString("rater_name")).isNull();
                    assertThat(rows.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(ratedAt.toInstant());
                    assertThat(rows.getObject("created_by", UUID.class)).isEqualTo(buyer);
                    assertThat(rows.getObject("updated_by", UUID.class)).isEqualTo(buyer);
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM user_reputations")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("id", UUID.class)).isEqualTo(reputation);
                    assertThat(rows.getObject("user_id", UUID.class)).isEqualTo(seller);
                    assertThat(rows.getInt("score")).isEqualTo(73);
                    assertThat(rows.getString("trust_level")).isEqualTo("TRUSTED");
                    assertThat(rows.getInt("total_transaction")).isEqualTo(12);
                    assertThat(rows.getInt("successful_transaction")).isEqualTo(10);
                    assertThat(rows.getInt("failed_transaction")).isEqualTo(2);
                    assertThat(rows.getLong("version")).isEqualTo(9);
                    assertThat(rows.getObject("created_by", UUID.class)).isEqualTo(actor);
                    assertThat(rows.getObject("updated_by", UUID.class)).isEqualTo(actor);
                    for (String column : List.of("rating_count", "rating_score_sum", "penalty_points",
                            "successful_auction_count", "failed_auction_count")) assertThat(rows.getLong(column)).isZero();
                    assertThat(rows.getBigDecimal("adjustment_offset")).isZero();
                    assertThat(rows.getString("policy_version")).isNull();
                    assertThat(rows.next()).isFalse();
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM rating_eligibility")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("order_id", UUID.class)).isEqualTo(order);
                    assertThat(rows.getObject("buyer_id", UUID.class)).isEqualTo(buyer);
                    assertThat(rows.getObject("seller_id", UUID.class)).isEqualTo(seller);
                    assertThat(rows.getObject("auction_id", UUID.class)).isEqualTo(auction);
                    assertThat(rows.getObject("source_event_id", UUID.class)).isEqualTo(completionEvent);
                    assertThat(rows.getLong("source_version")).isEqualTo(7);
                    assertThat(rows.getObject("completed_at", OffsetDateTime.class).toInstant()).isEqualTo(completedAt.toInstant());
                    assertThat(rows.getObject("created_by", UUID.class)).isEqualTo(actor);
                    assertThat(rows.getObject("updated_by", UUID.class)).isEqualTo(actor);
                    assertThat(rows.next()).isFalse();
                }
                for (String table : List.of("reputation_history", "reputation_effects", "reputation_sources",
                        "reputation_outcomes", "reputation_adjustment_requests", "outbox")) {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).as(table).isZero();
                    }
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM processed_events")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject("event_id", UUID.class)).isEqualTo(completionEvent);
                    assertThat(rows.getString("consumer")).isEqualTo("rating-service.order-completed");
                    assertThat(rows.next()).isFalse();
                }
                var indexes = new ArrayList<String>();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname='public'")) {
                    while (rows.next()) indexes.add(rows.getString(1));
                }
                assertThat(indexes).contains("uq_ratings_from_order", "idx_ratings_recipient_created",
                        "idx_reputation_history_user_time", "uq_reputation_history_source");
            }
        } finally {
            postgres.stop();
        }
    }
}
