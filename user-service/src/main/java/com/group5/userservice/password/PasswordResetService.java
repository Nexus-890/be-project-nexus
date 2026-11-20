package com.group5.userservice.password;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.KeycloakAdminClient;
import com.group5.userservice.iam.UserEventPublisher;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final KeycloakAdminClient keycloak;
    private final PasswordResetMailer mailer;
    private final UserEventPublisher events;
    private final PasswordResetProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PasswordResetService(UserRepository users, KeycloakAdminClient keycloak, PasswordResetMailer mailer,
                                UserEventPublisher events, PasswordResetProperties properties, JdbcTemplate jdbc,
                                Clock clock, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.keycloak = keycloak;
        this.mailer = mailer;
        this.events = events;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void request(String email) {
        Optional<User> found = users.findByEmailAndDeletedAtIsNull(email.trim().toLowerCase(Locale.ROOT))
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        String token = newToken();
        String hash = hash(token);
        OffsetDateTime now = OffsetDateTime.now(clock);
        Boolean created = transaction.execute(status -> {
            jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user.getId());
            Integer recent = jdbc.queryForObject("""
                    SELECT count(*) FROM password_reset_links WHERE user_id = ? AND created_at > ?
                    """, Integer.class, user.getId(), now.minus(properties.getResendInterval()));
            if (recent != null && recent > 0) {
                return false;
            }
            jdbc.update("DELETE FROM password_reset_links WHERE user_id = ? AND used_at IS NULL", user.getId());
            jdbc.update("""
                    INSERT INTO password_reset_links (user_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?)
                    """, user.getId(), hash, now, now.plus(properties.getLinkLifetime()));
            return true;
        });
        if (Boolean.TRUE.equals(created) && !mailer.send(user.getEmail(), user.getFullName(), token)) {
            jdbc.update("DELETE FROM password_reset_links WHERE token_hash = ?", hash);
        }
    }

    public void confirm(String token, String newPassword) {
        String hash = hash(token);
        OffsetDateTime now = OffsetDateTime.now(clock);
        UUID userId = transaction.execute(status -> {
            List<UUID> owners = jdbc.queryForList("""
                    SELECT user_id FROM password_reset_links
                    WHERE token_hash = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE
                    """, UUID.class, hash, now);
            if (owners.isEmpty()) {
                throw new DomainException(UserErrorCode.USER_PASSWORD_RESET_LINK_INVALID);
            }
            UUID owner = owners.getFirst();
            users.findByIdAndDeletedAtIsNull(owner).filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .orElseThrow(() -> new DomainException(UserErrorCode.USER_PASSWORD_RESET_LINK_INVALID));
            jdbc.update("UPDATE password_reset_links SET used_at = ? WHERE user_id = ? AND used_at IS NULL", now, owner);
            events.publish("UserPasswordChanged", owner, owner, Map.of("method", "RESET_LINK"));
            keycloak.setOwnPassword(owner, newPassword);
            return owner;
        });
        keycloak.signOutEverywhere(userId);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
