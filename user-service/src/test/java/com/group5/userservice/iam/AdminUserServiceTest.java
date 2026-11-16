package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.userservice.iam.dto.AdminCreateUserRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import com.group5.userservice.shared.config.IamProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T07:00:00Z"), ZoneOffset.UTC);
    private static final KeycloakAdminClient.RealmRole BUYER = new KeycloakAdminClient.RealmRole("buyer-id", "BUYER");
    @Mock UserRepository users;
    @Mock KeycloakAdminClient keycloak;
    @Mock UserEventPublisher events;
    @Mock PlatformTransactionManager manager;
    @Mock TransactionStatus tx;
    @Mock AdminIdentityOperationJournal journal;
    private AdminUserService service;
    private IamProperties properties;
    private User user;

    /** Chuẩn bị profile thật và transaction giả để phân biệt lỗi nghiệp vụ với lỗi commit. */
    @BeforeEach void setUp() {
        properties = new IamProperties();
        service = new AdminUserService(users, keycloak, events, properties, CLOCK, manager, journal);
        lenient().when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(tx);
        user = new User(); user.setId(ID); user.setEmail("buyer@nexus.local"); user.setPhone("0900000001");
        user.setVersion(0L); user.setFullName("Buyer One"); user.setGender(Gender.MALE); user.setAddress("HCM");
        user.setDateOfBirth(LocalDate.of(2000, 1, 1));
        lenient().when(users.findForProfileUpdate(ID)).thenReturn(Optional.of(user));
        lenient().when(journal.prepare(anyString(), eq(ID), eq(ACTOR), anyLong())).thenReturn(ACTOR);
    }

    /** Admin tạo được profile cùng UUID Keycloak và hai audit event không chứa password. */
    @Test void createPersistsIdentityAndAudits() {
        givenCreate();
        var result = service.create(request(List.of("BUYER")), ACTOR);
        assertThat(result.id()).isEqualTo(ID);
        assertThat(result.roles()).containsExactly("BUYER");
        verify(users).saveAndFlush(argThat(saved -> saved.getId().equals(ID) && saved.getPhone().equals("0900000001")));
        verify(events).publish("UserRegistered", ID, ACTOR, Map.of("roles", List.of("BUYER")));
        verify(events).publish("UserRoleAssigned", ID, ACTOR, Map.of("previousRoles", List.of(), "roles", List.of("BUYER")));
        verify(manager).commit(tx);
        verify(keycloak, never()).deleteUser(any());
    }

    /** Duplicate email và phone đều dừng trước khi tạo tài khoản provider. */
    @ParameterizedTest @ValueSource(strings = {"email", "phone"})
    void duplicateCreateNeverChangesKeycloak(String field) {
        when(keycloak.realmRole("BUYER")).thenReturn(Optional.of(BUYER));
        if (field.equals("email")) when(users.existsByEmail("buyer@nexus.local")).thenReturn(true);
        else when(users.existsByPhone("0900000001")).thenReturn(true);
        assertThatThrownBy(() -> service.create(request(List.of("BUYER")), ACTOR)).isInstanceOf(DomainException.class)
                .hasMessageContaining(field);
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString());
        verifyNoInteractions(events);
    }

    /** Role ngoài bộ cố định hoặc bộ rỗng không được tác động đến provider/DB. */
    @Test void invalidRolesRejectedBeforeWrite() {
        assertThatThrownBy(() -> service.create(request(List.of("realm-admin")), ACTOR)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.assignRoles(ID, List.of(), ACTOR)).isInstanceOf(DomainException.class);
        verifyNoInteractions(keycloak, events, manager);
    }

    /** DB commit lỗi sau khi tạo identity phải xóa identity vừa tạo. */
    @Test void createCompensatesCommitFailure() {
        givenCreate();
        doThrow(new IllegalStateException("commit-failed")).when(manager).commit(tx);
        assertThatThrownBy(() -> service.create(request(List.of("BUYER")), ACTOR)).hasMessage("commit-failed");
        verify(keycloak).deleteUser(ID);
    }

    /** Lỗi gán role cũng phải xóa identity, không giữ tài khoản nửa chừng. */
    @Test void createCompensatesRoleFailure() {
        givenCreate();
        doThrow(new IllegalStateException("provider-failed")).when(keycloak).replaceRealmRoles(ID, List.of(BUYER));
        assertThatThrownBy(() -> service.create(request(List.of("BUYER")), ACTOR)).hasMessage("provider-failed");
        verify(keycloak).deleteUser(ID);
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    /** Update audit tên field thực đổi, không sao chép dữ liệu contact vào audit. */
    @Test void updateWhitelistedDetails() {
        when(keycloak.realmRoles(ID)).thenReturn(List.of("BUYER"));
        var result = service.update(ID, new UpdateProfileRequest(null, null, null, "New address", null), ACTOR);
        assertThat(result.address()).isEqualTo("New address");
        verify(events).publish("UserUpdated", ID, ACTOR, Map.of("changedFields", List.of("address")));
        verify(keycloak, never()).updateUser(any(), any(), any(), anyBoolean());
    }

    /** Email đang tồn tại trả 409 trước khi ghi profile hoặc gọi provider update. */
    @Test void duplicateUpdateIsRejected() {
        when(users.existsByEmailAndIdNot("taken@nexus.local", ID)).thenReturn(true);
        assertThatThrownBy(() -> service.update(ID, new UpdateProfileRequest(null, null, null, null, "taken@nexus.local"), ACTOR))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(events);
        verify(keycloak, never()).updateUser(any(), any(), any(), anyBoolean());
    }

    /** Commit DB lỗi sau email update phải khôi phục snapshot provider nguyên vẹn. */
    @Test void updateRestoresProviderAfterCommitFailure() {
        Map<String, Object> before = Map.of("email", "buyer@nexus.local", "enabled", true);
        when(keycloak.userState(ID)).thenReturn(before);
        doThrow(new IllegalStateException("commit-failed")).when(manager).commit(tx);
        assertThatThrownBy(() -> service.update(ID, new UpdateProfileRequest(null, null, null, null, "new@nexus.local"), ACTOR))
                .hasMessage("commit-failed");
        verify(keycloak).updateUser(ID, "new@nexus.local", "Buyer One", true);
        verify(keycloak).restoreUser(ID, before);
    }

    /** Policy mặc định xóa mềm và disable Keycloak, audit chỉ lưu mode/actor/target. */
    @Test void softDeleteDisablesIdentity() {
        when(keycloak.userState(ID)).thenReturn(Map.of("enabled", true));
        service.delete(ID, ACTOR);
        assertThat(user.getDeletedAt().toInstant()).isEqualTo(CLOCK.instant());
        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        verify(keycloak).updateUser(ID, user.getEmail(), user.getFullName(), false);
        verify(events).publish("UserDeleted", ID, ACTOR, Map.of("deletionMode", "SOFT"));
        verify(users, never()).delete(any(User.class));
    }

    /** Policy tắt soft delete xóa profile cứng nhưng vẫn giữ identity đã disable để có thể hoàn tác. */
    @Test void hardDeletePolicyRemovesOnlyProfile() {
        properties.setUserSoftDelete(false);
        when(keycloak.userState(ID)).thenReturn(Map.of("enabled", true));
        service.delete(ID, ACTOR);
        verify(users).delete(user);
        verify(keycloak).updateUser(ID, user.getEmail(), user.getFullName(), false);
        verify(keycloak, never()).deleteUser(ID);
        verify(events).publish("UserDeleted", ID, ACTOR, Map.of("deletionMode", "HARD"));
    }

    /** Delete thất bại ở commit phải khôi phục enabled trên Keycloak. */
    @Test void deleteRestoresEnabledAfterCommitFailure() {
        Map<String, Object> before = Map.of("enabled", true);
        when(keycloak.userState(ID)).thenReturn(before);
        doThrow(new IllegalStateException("commit-failed")).when(manager).commit(tx);
        assertThatThrownBy(() -> service.delete(ID, ACTOR)).hasMessage("commit-failed");
        verify(keycloak).restoreUser(ID, before);
    }

    /** Assign roles lỗi commit khôi phục bộ role cũ, kể cả thao tác xóa trước khi thêm bị lỗi. */
    @Test void roleChangeRestoresOldSet() {
        when(keycloak.realmRole("BUYER")).thenReturn(Optional.of(BUYER));
        var seller = new KeycloakAdminClient.RealmRole("seller-id", "SELLER");
        when(keycloak.assignedRealmRoles(ID)).thenReturn(List.of(seller));
        doThrow(new IllegalStateException("commit-failed")).when(manager).commit(tx);
        assertThatThrownBy(() -> service.assignRoles(ID, List.of("BUYER"), ACTOR)).hasMessage("commit-failed");
        verify(keycloak).replaceRealmRoles(ID, List.of(seller));
        verify(events).publish("UserRoleAssigned", ID, ACTOR, Map.of("previousRoles", List.of("SELLER"), "roles", List.of("BUYER")));
    }

    /** User không tồn tại không được thực hiện bất kỳ thay đổi credentials nào. */
    @Test void missingProfileStopsPasswordReset() {
        when(users.findForProfileUpdate(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR)).isInstanceOf(DomainException.class);
        verifyNoInteractions(keycloak, events);
    }

    /** Outbox lỗi phải dừng trước lệnh reset password không thể hoàn tác. */
    @Test void auditFailureStopsPasswordReset() {
        doThrow(new IllegalStateException("outbox-failed")).when(events).publish("UserPasswordChanged", ID, ACTOR, Map.of());
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR)).hasMessage("outbox-failed");
        verify(keycloak, never()).resetPassword(any(), anyString());
        verify(manager).rollback(tx);
    }

    /** Reset password chỉ diễn ra sau khi ghi audit và flush toàn bộ SQL local. */
    @Test void passwordIsLastRemoteOperation() {
        service.changePassword(ID, "new-password", ACTOR);
        var order = inOrder(events, users, keycloak, manager);
        order.verify(users).findForProfileUpdate(ID);
        order.verify(keycloak).userState(ID);
        order.verify(events).publish("UserPasswordChanged", ID, ACTOR, Map.of());
        order.verify(users).flush();
        order.verify(keycloak).resetPassword(ID, "new-password");
        order.verify(manager).commit(tx);
    }

    /** Unlock tài khoản không bị detector khóa trả 409 và không phát event. */
    @Test void unlockingUnlockedAccountIsConflict() {
        assertThatThrownBy(() -> service.unlock(ID, ACTOR)).isInstanceOf(DomainException.class)
                .hasMessageContaining("not locked");
        verify(keycloak, never()).unlock(any());
        verifyNoInteractions(events);
    }

    /** Unlock thực tế xóa brute-force state và đổi local LOCKED về ACTIVE. */
    @Test void unlockResetsRealProviderState() {
        user.setStatus(UserStatus.LOCKED);
        when(keycloak.isBruteForceLocked(ID)).thenReturn(true);
        service.unlock(ID, ACTOR);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(keycloak).unlock(ID);
        verify(events).publish("UserUnlocked", ID, ACTOR, Map.of());
    }

    /** Audit lỗi không được mở khóa provider để tránh thiếu bằng chứng quản trị. */
    @Test void unlockAuditFailureStopsRemoteCall() {
        when(keycloak.isBruteForceLocked(ID)).thenReturn(true);
        doThrow(new IllegalStateException("outbox-failed")).when(events).publish("UserUnlocked", ID, ACTOR, Map.of());
        assertThatThrownBy(() -> service.unlock(ID, ACTOR)).hasMessage("outbox-failed");
        verify(keycloak, never()).unlock(any());
    }

    /** Intent không commit được phải dừng trước mọi thay đổi credential remote. */
    @Test void passwordIntentFailureStopsBeforeRemote() {
        when(journal.prepare("UserPasswordChanged", ID, ACTOR, 0L))
                .thenThrow(new IllegalStateException("intent-commit-failed"));
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR))
                .hasMessage("intent-commit-failed");
        verify(keycloak, never()).resetPassword(any(), anyString());
    }

    /** Lỗi COMMIT sau remote trả mã đối soát, confirmation đã được ghi trước local completion. */
    @Test void passwordCommitFailureRetainsConfirmedOperation() {
        doThrow(new IllegalStateException("commit-failed")).when(manager).commit(tx);
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR))
                .isInstanceOfSatisfying(DomainException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo("USER_IDENTITY_RECONCILIATION_REQUIRED");
                    assertThat(failure.getProperties()).containsEntry("operationId", ACTOR.toString());
                });
        var order = inOrder(journal, keycloak, manager);
        order.verify(journal).prepare("UserPasswordChanged", ID, ACTOR, 0L);
        order.verify(keycloak).resetPassword(ID, "new-password");
        order.verify(journal).remoteSucceeded(ACTOR);
        order.verify(journal).complete(ACTOR);
        order.verify(manager).commit(tx);
    }

    /** Timeout giữ intent chưa xác nhận và không gọi remote lần hai. */
    @Test void remoteTimeoutNeverConfirmsOrReplaysPassword() {
        doThrow(new IllegalStateException("timeout")).when(keycloak).resetPassword(ID, "new-password");
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR)).hasMessage("timeout");
        verify(journal).prepare("UserPasswordChanged", ID, ACTOR, 0L);
        verify(journal, never()).remoteSucceeded(any());
        verify(journal, never()).complete(any());
        verify(keycloak, times(1)).resetPassword(ID, "new-password");
    }

    /** Response đã thành công nhưng confirmation không lưu được vẫn giữ intent, báo cần đối soát. */
    @Test void confirmationFailureDoesNotRepeatRemoteChange() {
        doThrow(new IllegalStateException("database-down")).when(journal).remoteSucceeded(ACTOR);
        assertThatThrownBy(() -> service.changePassword(ID, "new-password", ACTOR))
                .isInstanceOfSatisfying(DomainException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo("USER_IDENTITY_RECONCILIATION_REQUIRED"));
        verify(journal, never()).complete(any());
        verify(keycloak, times(1)).resetPassword(ID, "new-password");
    }
    /** Chuẩn bị provider với role BUYER tồn tại và UUID identity được cấp trước khi lưu SQL. */
    private void givenCreate() {
        when(keycloak.realmRole("BUYER")).thenReturn(Optional.of(BUYER));
        when(keycloak.createUser("buyer@nexus.local", "test-password", "Buyer One")).thenReturn(ID);
    }

    /** Tạo request hợp lệ, chuẩn hóa giống contract HTTP. */
    private static AdminCreateUserRequest request(List<String> roles) {
        return new AdminCreateUserRequest("buyer@nexus.local", "0900000001", "test-password", "Buyer One",
                "identity-test", "MALE", "HCM", "2000-01-01", roles);
    }
}
