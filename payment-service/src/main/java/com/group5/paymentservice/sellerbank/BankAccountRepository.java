package com.group5.paymentservice.sellerbank;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BankAccountRepository {
    private final JdbcTemplate jdbc;

    public Optional<BankAccountView> find(UUID id) {
        return jdbc.query("SELECT * FROM seller_bank WHERE id=?", BankAccountRepository::view, id).stream().findFirst();
    }

    public List<BankAccountView> list(UUID sellerId, int page, int size) {
        return jdbc.query("SELECT * FROM seller_bank WHERE seller_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                BankAccountRepository::view, sellerId, size, (long) page * size);
    }

    public long count(UUID sellerId) {
        return jdbc.queryForObject("SELECT count(*) FROM seller_bank WHERE seller_id=?", Long.class, sellerId);
    }

    public Optional<VerifiedBankAccount> verifiedDefault(UUID sellerId) {
        return jdbc.query("""
                SELECT * FROM seller_bank WHERE seller_id=? AND is_default AND active AND verification_status='VERIFIED'
                """, (rs, row) -> new VerifiedBankAccount(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class),
                rs.getString("bank_code"), rs.getString("account_number_encrypted"), rs.getString("account_last4"),
                rs.getString("account_holder_name")), sellerId).stream().findFirst();
    }

    public String encryptedNumber(UUID id) {
        return jdbc.queryForObject("SELECT account_number_encrypted FROM seller_bank WHERE id=?", String.class, id);
    }

    // Các thao tác đổi default và xác minh dùng cùng khóa để tránh hai tài khoản mặc định đồng thời.
    public void lockSeller(UUID sellerId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "seller-bank:" + sellerId);
    }

    public void clearDefault(UUID sellerId, UUID exceptId, UUID actor) {
        jdbc.update("""
                UPDATE seller_bank SET is_default=false,version=version+1,updated_at=now(),updated_by=?
                WHERE seller_id=? AND is_default AND active AND id<>?
                """, actor, sellerId, exceptId);
    }

    public void insert(UUID id, UUID seller, BankAccountRequest body, String ciphertext, boolean isDefault, boolean active) {
        jdbc.update("""
                INSERT INTO seller_bank(id,seller_id,bank_code,account_number_encrypted,account_last4,account_holder_name,
                                        is_default,active,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, seller, body.bankCode(), ciphertext, last4(body.accountNumber()), body.accountHolderName().trim(),
                isDefault, active, seller, seller);
    }

    public void update(UUID id, UUID seller, BankAccountRequest body, String ciphertext, boolean changed,
            boolean isDefault, boolean active) {
        jdbc.update("""
                UPDATE seller_bank SET bank_code=?,account_number_encrypted=?,account_last4=?,account_holder_name=?,
                    is_default=?,active=?,
                    verification_status=CASE WHEN ? THEN 'UNVERIFIED'::seller_bank_verification_status ELSE verification_status END,
                    verified_by=CASE WHEN ? THEN NULL ELSE verified_by END,
                    verified_at=CASE WHEN ? THEN NULL ELSE verified_at END,
                    rejection_reason=CASE WHEN ? THEN NULL ELSE rejection_reason END,
                    version=version+1,updated_at=now(),updated_by=? WHERE id=? AND seller_id=?
                """, body.bankCode(), ciphertext, last4(body.accountNumber()), body.accountHolderName().trim(), isDefault, active,
                changed, changed, changed, changed, seller, id, seller);
    }

    public void setDefault(UUID id, UUID actor) {
        jdbc.update("UPDATE seller_bank SET is_default=true,version=version+1,updated_at=now(),updated_by=? WHERE id=?", actor, id);
    }

    public int verify(UUID id, UUID actor, String status, String reason, long expectedVersion) {
        return jdbc.update("""
                UPDATE seller_bank SET verification_status=?::seller_bank_verification_status,verified_by=?,verified_at=now(),
                    rejection_reason=?,version=version+1,updated_at=now(),updated_by=? WHERE id=? AND version=?
                """, status, actor, reason, actor, id, expectedVersion);
    }

    private static String last4(String number) { return number.substring(number.length() - 4); }

    private static BankAccountView view(ResultSet rs, int row) throws SQLException {
        return new BankAccountView(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getString("bank_code"),
                rs.getString("account_last4"), rs.getString("account_holder_name"), rs.getString("verification_status"),
                rs.getBoolean("is_default"), rs.getBoolean("active"), rs.getString("rejection_reason"),
                rs.getObject("verified_by", UUID.class), rs.getObject("verified_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version"));
    }
}
