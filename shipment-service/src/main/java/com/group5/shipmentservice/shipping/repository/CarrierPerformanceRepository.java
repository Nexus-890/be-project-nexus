package com.group5.shipmentservice.shipping.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public class CarrierPerformanceRepository {
    private static final String TOTALS = """
            WITH scoped AS (
                SELECT s.carrier, s.shipment_status, s.created_at, s.handed_over_at, s.estimated_delivery_at,
                       (SELECT min(h.created_at) FROM shipment_history h
                        WHERE h.shipment_id = s.id AND h.shipment_status = 'DELIVERED') AS delivered_at
                FROM shipments s
                WHERE s.created_at >= :from AND s.created_at < :to
            )
            SELECT carrier,
                   count(*) AS shipments,
                   count(*) FILTER (WHERE shipment_status = 'DELIVERED') AS delivered,
                   count(*) FILTER (WHERE shipment_status IN ('DELIVERY_FAILED', 'RETURNING', 'RETURNED')) AS failed,
                   count(*) FILTER (WHERE shipment_status = 'CANCELLED') AS cancelled,
                   avg(extract(epoch FROM delivered_at - coalesce(handed_over_at, created_at)) / 3600.0)
                       FILTER (WHERE shipment_status = 'DELIVERED' AND delivered_at IS NOT NULL) AS average_hours,
                   count(*) FILTER (WHERE shipment_status = 'DELIVERED' AND delivered_at IS NOT NULL
                                      AND estimated_delivery_at IS NOT NULL) AS estimated,
                   count(*) FILTER (WHERE shipment_status = 'DELIVERED' AND delivered_at IS NOT NULL
                                      AND delivered_at <= estimated_delivery_at) AS on_time
            FROM scoped
            GROUP BY carrier
            """;


    private final NamedParameterJdbcTemplate jdbc;

    public CarrierPerformanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Totals> totals(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.query(TOTALS, new MapSqlParameterSource().addValue("from", from).addValue("to", to),
                (rs, n) -> new Totals(rs.getString("carrier"), rs.getLong("shipments"), rs.getLong("delivered"),
                        rs.getLong("failed"), rs.getLong("cancelled"), rs.getBigDecimal("average_hours"),
                        rs.getLong("estimated"), rs.getLong("on_time")));
    }

    public record Totals(String carrier, long shipments, long delivered, long failed, long cancelled,
                         BigDecimal averageHours, long estimated, long onTime) {
    }
}
