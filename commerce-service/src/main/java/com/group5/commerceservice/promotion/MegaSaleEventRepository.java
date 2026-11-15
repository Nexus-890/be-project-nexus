package com.group5.commerceservice.promotion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.group5.commerceservice.promotion.MegaSaleEvent.*;

@Repository
class MegaSaleEventRepository {
    private static final String EVENT = """
            SELECT id, name, description, banner_url, registration_start_at, registration_end_at,
                   start_at, end_at, status, registration_open_requested, created_at, updated_at
            FROM promo_mega_sale_events
            """;
    private static final String SELLER = """
            SELECT id, event_id, seller_id, status, registered_at, reviewed_by, reviewed_at, review_note, withdrawn_at
            FROM promo_event_sellers
            """;
    private static final String VISIBLE = """
            (CAST(:seller AS uuid) IS NULL OR (status = 'REGISTRATION_OPEN'
                AND registration_start_at <= :now AND registration_end_at > :now)
                OR EXISTS (SELECT 1 FROM promo_event_sellers s
                           WHERE s.event_id = promo_mega_sale_events.id AND s.seller_id = :seller))
            """;
    private final JdbcClient jdbc;

    MegaSaleEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MegaSaleEvent> visible(UUID id, UUID seller, OffsetDateTime now) {
        return jdbc.sql(EVENT + " WHERE id = :id AND " + VISIBLE)
                .param("id", id).param("seller", seller).param("now", now).query(MegaSaleEvent.class).optional();
    }

    Optional<MegaSaleEvent> lock(UUID id) {
        return jdbc.sql(EVENT + " WHERE id = :id FOR UPDATE")
                .param("id", id).query(MegaSaleEvent.class).optional();
    }

    Page<MegaSaleEvent> list(UUID seller, Status status, OffsetDateTime now, PageRequest page) {
        String filters = " WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS promo_mega_sale_event_status)) AND "
                + VISIBLE;
        String state = status == null ? null : status.name();
        var rows = jdbc.sql(EVENT + filters + " ORDER BY start_at DESC, id LIMIT :size OFFSET :offset")
                .param("seller", seller).param("now", now).param("status", state)
                .param("size", page.getPageSize()).param("offset", page.getOffset()).query(MegaSaleEvent.class).list();
        long total = jdbc.sql("SELECT count(*) FROM promo_mega_sale_events " + filters)
                .param("seller", seller).param("now", now).param("status", state).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    UUID create(UUID actor, Write request) {
        return fields(jdbc.sql("""
                INSERT INTO promo_mega_sale_events (name, description, banner_url, registration_start_at,
                                                  registration_end_at, start_at, end_at, status, created_by, updated_by)
                VALUES (:name, :description, :banner, :registrationStart, :registrationEnd,
                        :start, :end, 'DRAFT', :actor, :actor) RETURNING id
                """), request).param("actor", actor).query(UUID.class).single();
    }

    void edit(UUID id, UUID actor, Write request, OffsetDateTime now) {
        fields(jdbc.sql("""
                UPDATE promo_mega_sale_events
                SET name = :name, description = :description, banner_url = :banner,
                    registration_start_at = :registrationStart, registration_end_at = :registrationEnd,
                    start_at = :start, end_at = :end, updated_by = :actor, updated_at = :now
                WHERE id = :id
                """), request).param("id", id).param("actor", actor).param("now", now).update();
    }

    private static JdbcClient.StatementSpec fields(JdbcClient.StatementSpec sql, Write request) {
        return sql.param("name", request.name()).param("description", request.description()).param("banner", request.bannerUrl())
                .param("registrationStart", request.registrationStartAt()).param("registrationEnd", request.registrationEndAt())
                .param("start", request.startAt()).param("end", request.endAt());
    }

    void status(UUID id, UUID actor, Status status, boolean schedule, OffsetDateTime now) {
        jdbc.sql("""
                UPDATE promo_mega_sale_events SET status = CAST(:status AS promo_mega_sale_event_status),
                    registration_open_requested = :schedule, updated_by = :actor, updated_at = :now WHERE id = :id
                """).param("id", id).param("actor", actor).param("status", status.name())
                .param("schedule", schedule).param("now", now).update();
    }

    Optional<Seller> register(UUID event, UUID seller, OffsetDateTime now) {
        return jdbc.sql("""
                INSERT INTO promo_event_sellers(event_id, seller_id, status, registered_at, updated_at, created_by, updated_by)
                VALUES (:event, :seller, 'PENDING', :now, :now, :seller, :seller)
                ON CONFLICT (event_id, seller_id) DO NOTHING
                RETURNING id, event_id, seller_id, status, registered_at, reviewed_by, reviewed_at, review_note, withdrawn_at
                """).param("event", event).param("seller", seller).param("now", now).query(Seller.class).optional();
    }

    Optional<Seller> registration(UUID id) {
        return jdbc.sql(SELLER + " WHERE id = :id").param("id", id).query(Seller.class).optional();
    }

    Optional<Seller> approvedSeller(UUID event, UUID seller) {
        return jdbc.sql(SELLER + " WHERE event_id = :id AND seller_id = :seller AND status = 'APPROVED'")
                .param("id", event).param("seller", seller).query(Seller.class).optional();
    }

    Page<Seller> sellers(UUID id, SellerStatus status, PageRequest page) {
        String filters = " WHERE event_id = :id AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS promo_event_seller_status))";
        String state = status == null ? null : status.name();
        var rows = jdbc.sql(SELLER + filters + " ORDER BY registered_at DESC, id LIMIT :size OFFSET :offset")
                .param("id", id).param("status", state).param("size", page.getPageSize()).param("offset", page.getOffset())
                .query(Seller.class).list();
        long total = jdbc.sql("SELECT count(*) FROM promo_event_sellers" + filters)
                .param("id", id).param("status", state).query(Long.class).single();
        return new PageImpl<>(rows, page, total);
    }

    boolean withdraw(UUID event, UUID seller, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_event_sellers SET status = 'WITHDRAWN', withdrawn_at = :now, updated_at = :now, updated_by = :seller
                WHERE event_id = :event AND seller_id = :seller AND status IN ('PENDING', 'APPROVED')
                """).param("event", event).param("seller", seller).param("now", now).update() == 1;
    }

    Optional<Seller> review(UUID id, UUID actor, Review request, OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_event_sellers SET status = CAST(:status AS promo_event_seller_status),
                    reviewed_by = :actor, reviewed_at = :now, review_note = :note, updated_by = :actor, updated_at = :now
                WHERE id = :id AND status = 'PENDING'
                RETURNING id, event_id, seller_id, status, registered_at, reviewed_by, reviewed_at, review_note, withdrawn_at
                """).param("id", id).param("actor", actor).param("status", request.status().name())
                .param("note", request.reviewNote()).param("now", now).query(Seller.class).optional();
    }

    int advance(OffsetDateTime now) {
        return jdbc.sql("""
                UPDATE promo_mega_sale_events SET status = CAST(CASE
                    WHEN end_at <= :now THEN 'ENDED'
                    WHEN start_at <= :now THEN 'ACTIVE'
                    WHEN registration_end_at > :now THEN 'REGISTRATION_OPEN'
                    ELSE 'REGISTRATION_CLOSED'
                END AS promo_mega_sale_event_status), updated_at = :now
                WHERE (status = 'DRAFT' AND registration_open_requested AND registration_start_at <= :now)
                   OR (status = 'REGISTRATION_OPEN' AND registration_end_at <= :now)
                   OR (status = 'REGISTRATION_CLOSED' AND start_at <= :now)
                   OR (status = 'ACTIVE' AND end_at <= :now)
                """).param("now", now).update();
    }
}
