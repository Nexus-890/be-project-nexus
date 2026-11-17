package com.group5.userservice.phone;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.phone.dto.PhoneCodeSentResponse;
import com.group5.userservice.phone.dto.PhoneVerifiedResponse;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PhoneVerificationService {
    static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);
    static final int MAX_SENDS = 3;
    static final int MAX_ATTEMPTS = 5;

    private final UserRepository users;
    private final PhoneVerificationRepository verifications;
    private final SmsSender sms;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(UserRepository users, PhoneVerificationRepository verifications, SmsSender sms,
                                    Clock clock, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.verifications = verifications;
        this.sms = sms;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public PhoneCodeSentResponse send(UUID userId) {
        return transaction.execute(status -> {
            User user = lockUser(userId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (verifications.countByUserIdAndCreatedAtAfter(userId, now.minus(SEND_WINDOW)) >= MAX_SENDS) {
                throw new DomainException(UserErrorCode.PHONE_CODE_RATE_LIMITED);
            }
            String code = "%06d".formatted(random.nextInt(1_000_000));
            PhoneVerification verification = new PhoneVerification();
            verification.setId(UUID.randomUUID());
            verification.setUserId(userId);
            verification.setPhone(user.getPhone());
            verification.setCodeHash(hash(verification.getId(), code));
            verification.setExpiresAt(now.plus(CODE_LIFETIME));
            verification.setCreatedAt(now);
            verifications.saveAndFlush(verification);
            sms.send(user.getPhone(), "Your Nexus verification code is " + code + ". It expires in 5 minutes.");
            return new PhoneCodeSentResponse(user.getPhone(), verification.getExpiresAt());
        });
    }

    public PhoneVerifiedResponse confirm(UUID userId, String code) {
        Outcome outcome = transaction.execute(status -> check(userId, code));
        return switch (outcome.result()) {
            case VERIFIED -> outcome.response();
            case WRONG, NO_CODE -> throw new DomainException(UserErrorCode.PHONE_CODE_INVALID);
            case EXPIRED -> throw new DomainException(UserErrorCode.PHONE_CODE_EXPIRED);
            case LOCKED -> throw new DomainException(UserErrorCode.PHONE_CODE_RATE_LIMITED);
        };
    }

    private Outcome check(UUID userId, String code) {
        User user = lockUser(userId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        PhoneVerification verification = verifications.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .filter(candidate -> candidate.getVerifiedAt() == null)
                .filter(candidate -> candidate.getPhone().equals(user.getPhone()))
                .orElse(null);
        if (verification == null) {
            return new Outcome(Result.NO_CODE, null);
        }
        if (verification.getAttempts() >= MAX_ATTEMPTS) {
            return new Outcome(Result.LOCKED, null);
        }
        if (!now.isBefore(verification.getExpiresAt())) {
            return new Outcome(Result.EXPIRED, null);
        }
        if (!MessageDigest.isEqual(hash(verification.getId(), code).getBytes(StandardCharsets.US_ASCII),
                verification.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
            verification.setAttempts(verification.getAttempts() + 1);
            return new Outcome(Result.WRONG, null);
        }
        verification.setVerifiedAt(now);
        user.setPhoneVerifiedAt(now);
        return new Outcome(Result.VERIFIED, new PhoneVerifiedResponse(user.getPhone(), now));
    }

    private User lockUser(UUID userId) {
        return users.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
    }

    static String hash(UUID verificationId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((verificationId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum Result { VERIFIED, WRONG, NO_CODE, EXPIRED, LOCKED }

    private record Outcome(Result result, PhoneVerifiedResponse response) { }
}
