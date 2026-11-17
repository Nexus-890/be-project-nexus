package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class EmailVerificationService {
    static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

    private final KeycloakAdminClient keycloak;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EmailVerificationService(KeycloakAdminClient keycloak, UserRepository users, JdbcTemplate jdbc,
                                    Clock clock) {
        this.keycloak = keycloak;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void sendAfterRegistration(UUID userId, String email) {
        claimSend(normalize(email));
        keycloak.sendVerifyEmail(userId);
    }

    public void resend(String email) {
        String normalized = normalize(email);
        if (!claimSend(normalized)) {
            throw new DomainException(UserErrorCode.USER_VERIFICATION_EMAIL_RATE_LIMITED);
        }
        users.findByEmailAndDeletedAtIsNull(normalized)
                .filter(user -> keycloak.emailState(user.getId())
                        .map(state -> !state.emailVerified())
                        .orElse(false))
                .ifPresent(user -> keycloak.sendVerifyEmail(user.getId()));
    }

    private boolean claimSend(String email) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return jdbc.update("""
                INSERT INTO verification_email_sends (email, sent_at) VALUES (?, ?)
                ON CONFLICT (email) DO UPDATE SET sent_at = EXCLUDED.sent_at
                WHERE verification_email_sends.sent_at <= ?
                """, email, now, now.minus(RESEND_INTERVAL)) == 1;
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
