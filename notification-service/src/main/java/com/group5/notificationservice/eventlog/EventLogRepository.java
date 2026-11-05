package com.group5.notificationservice.eventlog;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class EventLogRepository {
    private static final Map<String, String> SORTS = Map.of(
            "occurredAt", "occurred_at", "receivedAt", "received_at", "eventType", "event_type");
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper;

    public EventLogRepository(NamedParameterJdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ApiResponse<List<EventLogSummary>> events(String q, String eventType, String aggregateType,
            String aggregateId, String operatorId, String from, String to, String sort, int page, int size) {
        Query query = query(from, to, page, size);
        query.equal("event_type", eventType);
        query.equal("aggregate_type", aggregateType);
        query.equal("aggregate_id", aggregateId);
        query.equal("operator_id", uuid(operatorId));
        if (q != null && !q.isBlank()) {
            query.where.append(" AND strpos(lower(concat_ws(' ', event_id, event_type, aggregate_type, aggregate_id, operator_id, operator_name, trace_id, payload::text)), lower(:q)) > 0");
            query.params.addValue("q", q);
        }
        String order = order(sort);
        long total = count("event_logs", query);
        var rows = jdbc.query("SELECT * FROM event_logs" + query.where + " ORDER BY " + order + ", id DESC LIMIT :limit OFFSET :offset",
                query.params, (rs, row) -> summary(rs));
        return ApiResponse.page(rows, pageInfo(page, size, total));
    }

    public EventLogDetail detail(String id) {
        UUID parsed = uuid(id);
        var rows = jdbc.query("SELECT * FROM event_logs WHERE id = :id", Map.of("id", parsed), (rs, row) -> {
            JsonNode envelope = mapper.readTree(rs.getString("payload"));
            return new EventLogDetail(summary(rs), envelope.get("payload"), envelope, rs.getString("raw_envelope"));
        });
        if (rows.isEmpty()) throw new DomainException(NotificationErrorCode.EVENT_LOG_NOT_FOUND);
        return rows.getFirst();
    }

    public ApiResponse<List<AuditLog>> audits(String actorId, String action, String entityType,
            String entityId, String from, String to, int page, int size) {
        Query query = query(from, to, page, size);
        query.equal("actor_id", uuid(actorId));
        query.equal("action", action);
        query.equal("entity_type", entityType);
        query.equal("entity_id", entityId);
        long total = count("audit_logs", query);
        var rows = jdbc.query("SELECT * FROM audit_logs" + query.where + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
                query.params, (rs, row) -> new AuditLog(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                        rs.getObject("actor_id", UUID.class), rs.getString("action"), rs.getString("entity_type"),
                        rs.getString("entity_id"), rs.getString("trace_id"), instant(rs, "occurred_at"), instant(rs, "received_at")));
        return ApiResponse.page(rows, pageInfo(page, size, total));
    }

    private long count(String table, Query query) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + query.where, query.params, Long.class);
    }

    private static Query query(String from, String to, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid();
        Instant start = time(from), end = time(to);
        if (start != null && end != null && start.isAfter(end)) throw invalid();
        var query = new Query();
        query.params.addValue("limit", size).addValue("offset", (long) page * size);
        if (start != null) {
            query.where.append(" AND occurred_at >= :from");
            query.params.addValue("from", Timestamp.from(start));
        }
        if (end != null) {
            query.where.append(" AND occurred_at <= :to");
            query.params.addValue("to", Timestamp.from(end));
        }
        return query;
    }

    private static String order(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length != 2 || !SORTS.containsKey(parts[0]) ||
                !(parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"))) throw invalid();
        return SORTS.get(parts[0]) + " " + parts[1].toUpperCase(java.util.Locale.ROOT);
    }

    private static UUID uuid(String value) {
        if (value == null) return null;
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static Instant time(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (java.time.DateTimeException e) { throw invalid(); }
    }

    private static DomainException invalid() { return new DomainException(NotificationErrorCode.DATA_INVALID); }
    private static PageInfo pageInfo(int page, int size, long count) {
        return new PageInfo(page, size, count, (int) ((count + size - 1) / size));
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException { return rs.getTimestamp(field).toInstant(); }
    private static EventLogSummary summary(ResultSet rs) throws SQLException {
        return new EventLogSummary(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), rs.getString("aggregate_type"), rs.getString("aggregate_id"),
                rs.getObject("operator_id", UUID.class), rs.getString("trace_id"), rs.getString("source_topic"),
                instant(rs, "occurred_at"), instant(rs, "received_at"));
    }

    private static class Query {
        final StringBuilder where = new StringBuilder(" WHERE 1=1");
        final MapSqlParameterSource params = new MapSqlParameterSource();
        void equal(String column, Object value) {
            if (value != null) {
                where.append(" AND ").append(column).append(" = :").append(column);
                params.addValue(column, value);
            }
        }
    }

    public record EventLogSummary(UUID id, UUID eventId, String eventType, String aggregateType,
            String aggregateId, UUID operatorId, String traceId, String topic, Instant occurredAt, Instant receivedAt) { }
    public record EventLogDetail(EventLogSummary event, JsonNode payload, JsonNode envelope, String rawEnvelope) { }
    public record AuditLog(UUID id, UUID eventId, UUID actorId, String action, String entityType,
            String entityId, String traceId, Instant occurredAt, Instant receivedAt) { }
}
