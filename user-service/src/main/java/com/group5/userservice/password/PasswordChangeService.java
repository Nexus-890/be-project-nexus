package com.group5.userservice.password;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.KeycloakAdminClient;
import com.group5.userservice.iam.UserEventPublisher;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Service
public class PasswordChangeService {
    private final UserRepository users;
    private final KeycloakAdminClient keycloak;
    private final UserEventPublisher events;
    private final TransactionTemplate transaction;

    public PasswordChangeService(UserRepository users, KeycloakAdminClient keycloak, UserEventPublisher events,
                                 PlatformTransactionManager transactionManager) {
        this.users = users;
        this.keycloak = keycloak;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void change(UUID userId, String sessionId, String currentPassword, String newPassword) {
        User user = users.findByIdAndDeletedAtIsNull(userId).filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
        if (currentPassword.equals(newPassword)) {
            throw new DomainException(UserErrorCode.USER_PASSWORD_REUSED);
        }
        if (!keycloak.passwordMatches(user.getEmail(), currentPassword)) {
            throw new DomainException(UserErrorCode.USER_CURRENT_PASSWORD_INCORRECT);
        }
        transaction.executeWithoutResult(status -> {
            events.publish("UserPasswordChanged", userId, userId, Map.of("method", "SELF"));
            keycloak.setOwnPassword(userId, newPassword);
        });
        keycloak.signOutOtherSessions(userId, sessionId);
    }
}
