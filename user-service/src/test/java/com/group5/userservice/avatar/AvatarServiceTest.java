package com.group5.userservice.avatar;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.UserService;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarServiceTest {
    private static final UUID ID = UUID.fromString("64fba061-3a91-4a35-a853-221798344510");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};
    private static final String OLD_URL = "https://storage.example/avatars/" + ID + "/old.png";
    private static final String NEW_URL = "https://storage.example/avatars/" + ID + "/new.jpg";
    private final AvatarStorage storage = mock(AvatarStorage.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserService profiles = mock(UserService.class);
    private final AvatarProperties properties = new AvatarProperties();
    private final User user = new User();
    private AvatarService service;

    @BeforeEach
    void setUp() {
        properties.setMaxFileSize(DataSize.ofBytes(16));
        user.setId(ID);
        user.setAvatarUrl(OLD_URL);
        when(users.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.of(user));
        when(storage.upload(eq(ID), any())).thenReturn(NEW_URL);
        service = new AvatarService(storage, properties, users, profiles, mock(PlatformTransactionManager.class));
    }

    @Test
    void replacingStoresTheNewUrlThenRemovesTheOldObject() {
        UserResponse profile = mock(UserResponse.class);
        when(profiles.getOwnProfile(ID)).thenReturn(profile);

        assertThat(service.replace(ID, file("me.png", JPEG))).isSameAs(profile);

        assertThat(user.getAvatarUrl()).isEqualTo(NEW_URL);
        verify(users).saveAndFlush(user);
        verify(storage).discard(ID, OLD_URL);
        verify(storage, never()).discard(ID, NEW_URL);
    }

    @Test
    void theDetectedTypeWinsOverTheClaimedOneAndBadFilesNeverReachStorage() {
        expect("USER_AVATAR_INVALID_TYPE", file("me.jpg", "<svg/>".getBytes(StandardCharsets.US_ASCII)));
        expect("USER_AVATAR_REQUIRED", file("me.jpg", new byte[0]));
        expect("USER_AVATAR_REQUIRED", null);
        expect("USER_AVATAR_TOO_LARGE", file("me.jpg", new byte[17]));
        verify(storage, never()).upload(any(), any());
    }

    @Test
    void aFailedSaveRemovesTheJustUploadedObjectAndKeepsTheOldAvatar() {
        when(users.saveAndFlush(user)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service.replace(ID, file("me.jpg", JPEG))).isInstanceOf(IllegalStateException.class);

        verify(storage).discard(ID, NEW_URL);
        verify(storage, never()).discard(ID, OLD_URL);
    }

    @Test
    void anUnknownUserIsRejectedBeforeUploading() {
        when(users.findByIdAndDeletedAtIsNull(ID)).thenReturn(Optional.empty());
        expect("USER_NOT_FOUND", file("me.jpg", JPEG));
        verify(storage, never()).upload(any(), any());
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "image/jpeg", content);
    }

    private void expect(String code, MockMultipartFile file) {
        assertThatThrownBy(() -> service.replace(ID, file))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
