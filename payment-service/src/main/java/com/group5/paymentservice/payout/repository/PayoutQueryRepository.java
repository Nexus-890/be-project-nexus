package com.group5.paymentservice.payout.repository;

import com.group5.paymentservice.payout.dto.request.PayoutListRequest;
import com.group5.paymentservice.payout.dto.response.PayoutView;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PayoutQueryRepository {
    private final JdbcTemplate jdbc;

    public long countAdmin(PayoutListRequest request) {
        List<Object> parameters = new ArrayList<>();
        String filter = adminFilter(request, parameters);
        return jdbc.queryForObject(
                "SELECT count(*) FROM payouts" + filter, Long.class, parameters.toArray());
    }

    public List<PayoutView> findAdmin(PayoutListRequest request) {
        List<Object> parameters = new ArrayList<>();
        String filter = adminFilter(request, parameters);
        parameters.add(request.size());
        parameters.add((long) request.page() * request.size());
        return jdbc.query(
                "SELECT * FROM payouts"
                        + filter
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> view(rs),
                parameters.toArray());
    }

    private static String adminFilter(PayoutListRequest request, List<Object> parameters) {
        StringBuilder filter = new StringBuilder(" WHERE 1=1");
        if (request.sellerId() != null) {
            filter.append(" AND seller_id=?");
            parameters.add(request.sellerId());
        }
        if (!request.status().isEmpty()) {
            filter.append(" AND status IN (")
                    .append(
                            String.join(
                                    ",",
                                    Collections.nCopies(
                                            request.status().size(), "?::payout_status")))
                    .append(')');
            request.status().forEach(status -> parameters.add(status.name()));
        }
        if (request.from() != null) {
            filter.append(" AND created_at>=?");
            parameters.add(request.from());
        }
        if (request.to() != null) {
            filter.append(" AND created_at<=?");
            parameters.add(request.to());
        }
        return filter.toString();
    }

    public long countBySeller(UUID sellerId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payouts WHERE seller_id=?", Long.class, sellerId);
    }

    public List<PayoutView> findBySeller(UUID sellerId, int page, int size) {
        return jdbc.query(
                """
                SELECT * FROM payouts WHERE seller_id=?
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """,
                (rs, row) -> view(rs),
                sellerId,
                size,
                (long) page * size);
    }

    public Optional<PayoutView> findById(UUID id) {
        return jdbc.query("SELECT * FROM payouts WHERE id=?", (rs, row) -> view(rs), id).stream()
                .findFirst();
    }

    public List<PayoutView.History> findHistory(UUID payoutId) {
        return jdbc.query(
                "SELECT * FROM payout_history WHERE payout_id=? ORDER BY event_seq DESC",
                (rs, row) -> history(rs),
                payoutId);
    }

    private static PayoutView view(ResultSet rs) throws SQLException {
        return new PayoutView(
                rs.getObject("id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("seller_id", UUID.class),
                rs.getBigDecimal("order_amount"),
                rs.getBigDecimal("shipping_fee"),
                rs.getBigDecimal("platform_fee_rate"),
                rs.getBigDecimal("platform_fee"),
                rs.getBigDecimal("net_amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getString("recipient_account_last4"),
                rs.getString("provider_reference"),
                "sandbox".equals(rs.getString("provider")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                null,
                rs.getString("execution_mode"),
                rs.getString("confirmation_source"),
                null);
    }

    private static PayoutView.History history(ResultSet rs) throws SQLException {
        return new PayoutView.History(
                rs.getObject("id", UUID.class),
                rs.getString("event"),
                rs.getLong("event_seq"),
                rs.getString("from_status"),
                rs.getString("to_status"),
                rs.getInt("attempt_no"),
                rs.getBigDecimal("amount"),
                rs.getObject("seller_bank_id", UUID.class),
                rs.getString("recipient_account_last4"),
                rs.getString("provider"),
                rs.getString("provider_reference"),
                rs.getString("provider_status"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getBoolean("simulated"),
                rs.getObject("actor_id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("transaction_id", UUID.class));
    }
}
