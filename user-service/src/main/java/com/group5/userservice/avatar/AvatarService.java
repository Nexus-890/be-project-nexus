package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.UserService;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
public class AvatarService {
    private final AvatarStorage storage;
    private final AvatarProperties properties;
    private final UserRepository users;
    private final UserService profiles;
    private final TransactionTemplate transaction;

    public AvatarService(AvatarStorage storage, AvatarProperties properties, UserRepository users,
                         UserService profiles, PlatformTransactionManager transactionManager) {
        this.storage = storage;
        this.properties = properties;
        this.users = users;
        this.profiles = profiles;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public UserResponse replace(UUID userId, MultipartFile file) {
        AvatarImage image = read(file);
        if (users.findByIdAndDeletedAtIsNull(userId).isEmpty()) {
            throw new DomainException(UserErrorCode.USER_NOT_FOUND);
        }
        String url = storage.upload(userId, image);
        String previous;
        try {
            previous = transaction.execute(status -> {
                User user = users.findByIdAndDeletedAtIsNull(userId)
                        .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
                String old = user.getAvatarUrl();
                user.setAvatarUrl(url);
                users.saveAndFlush(user);
                return old;
            });
        } catch (RuntimeException failure) {
            storage.discard(userId, url);
            throw failure;
        }
        storage.discard(userId, previous);
        return profiles.getOwnProfile(userId);
    }

    private AvatarImage read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(UserErrorCode.USER_AVATAR_REQUIRED);
        }
        long limit = properties.getMaxFileSize().toBytes();
        if (file.getSize() > limit) {
            throw new DomainException(UserErrorCode.USER_AVATAR_TOO_LARGE);
        }
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes((int) limit + 1);
        } catch (IOException e) {
            throw new DomainException(UserErrorCode.USER_AVATAR_REQUIRED);
        }
        if (bytes.length > limit) {
            throw new DomainException(UserErrorCode.USER_AVATAR_TOO_LARGE);
        }
        return AvatarImage.detect(bytes).orElseThrow(() -> new DomainException(UserErrorCode.USER_AVATAR_INVALID_TYPE));
    }
}
