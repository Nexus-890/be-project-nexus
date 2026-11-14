package com.group5.userservice.iam;

import com.group5.common_core.exception.DomainException;
import com.group5.common_events.EventEnvelopeFactory;
import com.group5.common_outbox.OutboxWriter;
import com.group5.userservice.iam.dto.AdminCreateUserRequest;
import com.group5.userservice.iam.dto.UpdateProfileRequest;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.shared.PostgresAuditingTestConfiguration;
import com.group5.userservice.shared.PostgresTestDatabase;
import com.group5.userservice.shared.config.IamProperties;
import com.group5.userservice.shared.exception.UserErrorCode;
import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PostgreSQL thật kiểm chứng commit/rollback của profile và outbox; chỉ Admin API được giả lập. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminUserTransactionTest.JpaConfig.class)
class AdminUserTransactionTest {
    private static final UUID TARGET = UUID.fromString("35754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final UUID CREATED = UUID.fromString("45754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final UUID ACTOR = UUID.fromString("55754f22-99b9-4a70-b8ea-612b876ba5ba");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final KeycloakAdminClient.RealmRole BUYER = new KeycloakAdminClient.RealmRole("buyer-id", "BUYER");
    private static final KeycloakAdminClient.RealmRole SELLER = new KeycloakAdminClient.RealmRole("seller-id", "SELLER");

    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DataSource source;
    @Autowired private UserEventPublisher events;

    private final Map<UUID, Map<String, Object>> identities = new HashMap<>();
    private final Map<UUID, List<KeycloakAdminClient.RealmRole>> roles = new HashMap<>();
    private KeycloakAdminClient keycloak;
    private IamProperties properties;
    private AdminUserService service;
    private JdbcTemplate jdbc;

    /** Chuẩn bị schema riêng và remote giả có trạng thái để quan sát hiệu ứng hoàn tác thực tế. */
    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("drop trigger if exists admin_test_commit_failure on outbox");
        jdbc.execute("alter table outbox drop constraint if exists admin_test_outbox_failure");
        jdbc.execute("truncate users, outbox cascade");
        jdbc.update("""
                insert into users (id,email,phone,full_name,identity_number,gender,address,date_of_birth,status)
                values (?, 'original@nexus.local', '0900000001', 'Original User', 'ID-01', 'MALE', 'HCM', '2000-01-02', 'ACTIVE')
                """, TARGET);
        identities.clear();
        identities.put(TARGET, new LinkedHashMap<>(Map.of("email", "original@nexus.local",
                "firstName", "Original", "lastName", "User", "enabled", true, "emailVerified", true)));
        roles.clear();
        roles.put(TARGET, List.of(BUYER));
        keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.realmRole("BUYER")).thenReturn(Optional.of(BUYER));
        when(keycloak.realmRole("SELLER")).thenReturn(Optional.of(SELLER));
        when(keycloak.realmRoles(any())).thenAnswer(call -> assigned(call.getArgument(0)).stream()
                .map(KeycloakAdminClient.RealmRole::name).sorted().toList());
        when(keycloak.assignedRealmRoles(any())).thenAnswer(call -> assigned(call.getArgument(0)));
        when(keycloak.userState(any())).thenAnswer(call -> new LinkedHashMap<>(identities.get(call.getArgument(0))));
        when(keycloak.createUser(anyString(), anyString(), anyString())).thenAnswer(call -> {
            identities.put(CREATED, new LinkedHashMap<>(Map.of("email", call.getArgument(0),
                    "enabled", true, "firstName", call.getArgument(2), "lastName", "")));
            return CREATED;
        });
        doAnswer(call -> { identities.remove(call.getArgument(0)); roles.remove(call.getArgument(0)); return null; })
                .when(keycloak).deleteUser(any());
        doAnswer(call -> { roles.put(call.getArgument(0), List.copyOf(call.getArgument(1))); return null; })
                .when(keycloak).replaceRealmRoles(any(), anyList());
        doAnswer(call -> {
            Map<String, Object> state = identities.get(call.getArgument(0));
            state.put("email", call.getArgument(1)); state.put("fullName", call.getArgument(2));
            state.put("enabled", call.getArgument(3)); return null;
        }).when(keycloak).updateUser(any(), anyString(), anyString(), anyBoolean());
        doAnswer(call -> { identities.put(call.getArgument(0), new LinkedHashMap<>(call.getArgument(1))); return null; })
                .when(keycloak).restoreUser(any(), any());
        properties = new IamProperties();
        service = new AdminUserService(users, keycloak, events, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), manager);
    }

    /** Tạo tài khoản phải commit profile, role và hai audit event dùng cùng UUID với Keycloak. */
    @Test
    void createCommitsProfileRolesAndBothAuditEvents() {
        UserResponse response = service.create(createRequest(), ACTOR);

        assertThat(response.id()).isEqualTo(CREATED);
        assertThat(response.roles()).containsExactly("BUYER", "SELLER");
        assertThat(snapshot(CREATED).get("email")).isEqualTo("created@nexus.local");
        assertThat(identities.get(CREATED).get("enabled")).isEqualTo(true);
        assertThat(assigned(CREATED)).containsExactly(BUYER, SELLER);
        assertAudit("UserRegistered", CREATED);
        assertAudit("UserRoleAssigned", CREATED);
        assertThat(auditCount()).isEqualTo(2);
        assertThat(auditPayload("UserRoleAssigned").get("previousRoles").isEmpty()).isTrue();
    }

    /** Ràng buộc outbox thất bại phải rollback user mới và xóa tài khoản vừa tạo trên Keycloak. */
    @Test
    void createOutboxFailureRollsBackProfileAndCompensatesIdentity() {
        rejectOutboxWrites();

        assertThatThrownBy(() -> service.create(createRequest(), ACTOR)).isInstanceOf(RuntimeException.class);

        assertThat(countUser(CREATED)).isZero();
        assertThat(identities).doesNotContainKey(CREATED);
        assertThat(auditCount()).isZero();
        verify(keycloak).deleteUser(CREATED);
    }

    /** Lỗi PostgreSQL chỉ phát sinh ở COMMIT vẫn phải xóa identity và rollback cả profile lẫn outbox. */
    @Test
    void deferredCommitFailureCompensatesCreatedIdentity() {
        failAtCommit();

        assertThatThrownBy(() -> service.create(createRequest(), ACTOR)).isInstanceOf(RuntimeException.class);

        assertThat(countUser(CREATED)).isZero();
        assertThat(auditCount()).isZero();
        assertThat(identities).doesNotContainKey(CREATED);
        verify(keycloak).deleteUser(CREATED);
    }

    /** Update thành công ghi audit tối thiểu; request lặp lại không tạo thêm event giả. */
    @Test
    void updateCommitsChangedFieldsAndRepeatsWithoutAnotherAudit() {
        var request = new UpdateProfileRequest("Changed User", null, null, "Ha Noi", "changed@nexus.local");
        service.update(TARGET, request, ACTOR);
        service.update(TARGET, request, ACTOR);

        assertThat(snapshot(TARGET).get("email")).isEqualTo("changed@nexus.local");
        assertThat(snapshot(TARGET).get("address")).isEqualTo("Ha Noi");
        assertThat(identities.get(TARGET).get("email")).isEqualTo("changed@nexus.local");
        assertAudit("UserUpdated", TARGET);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(auditPayload("UserUpdated").get("changedFields").toString())
                .contains("email", "fullName", "address");
    }

    /** Lỗi audit sau khi đổi email remote phải khôi phục snapshot identity và toàn bộ profile local. */
    @Test
    void updateOutboxFailureRestoresIdentityAndProfile() {
        Map<String, Object> before = snapshot(TARGET);
        Map<String, Object> identityBefore = Map.copyOf(identities.get(TARGET));
        rejectOutboxWrites();

        assertThatThrownBy(() -> service.update(TARGET,
                new UpdateProfileRequest("Rejected", null, null, "Rejected", "new@nexus.local"), ACTOR))
                .isInstanceOf(RuntimeException.class);

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(identities.get(TARGET)).isEqualTo(identityBefore);
        assertThat(auditCount()).isZero();
        verify(keycloak).restoreUser(TARGET, identityBefore);
    }

    /** COMMIT thất bại sau khi remote đã đổi vẫn khôi phục email, tên và trạng thái xác minh ban đầu. */
    @Test
    void deferredUpdateCommitFailureRestoresBothSides() {
        Map<String, Object> before = snapshot(TARGET);
        Map<String, Object> identityBefore = Map.copyOf(identities.get(TARGET));
        failAtCommit();

        assertThatThrownBy(() -> service.update(TARGET,
                new UpdateProfileRequest("Rejected", null, null, null, "new@nexus.local"), ACTOR))
                .isInstanceOf(RuntimeException.class);

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(identities.get(TARGET)).isEqualTo(identityBefore);
        assertThat(auditCount()).isZero();
    }

    /** Keycloak từ chối profile phải rollback các thay đổi JPA đã flush và không để lại audit. */
    @Test
    void updateProviderFailureRollsBackFlushedLocalProfile() {
        Map<String, Object> before = snapshot(TARGET);
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_REJECTED)).when(keycloak)
                .updateUser(eq(TARGET), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> service.update(TARGET,
                new UpdateProfileRequest("Rejected", null, null, null, null), ACTOR))
                .isInstanceOf(DomainException.class);

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    /** Xóa mềm giữ dòng DELETED, vô hiệu đăng nhập và lưu actor độc lập với dữ liệu profile. */
    @Test
    void softDeleteCommitsDeletedMarkerDisabledIdentityAndAudit() {
        service.delete(TARGET, ACTOR);

        Map<String, Object> deleted = snapshot(TARGET);
        assertThat(deleted.get("status")).isEqualTo("DELETED");
        assertThat(deleted.get("deleted_at")).isNotNull();
        assertThat(identities.get(TARGET).get("enabled")).isEqualTo(false);
        assertAudit("UserDeleted", TARGET);
        assertThat(auditPayload("UserDeleted").get("deletionMode").asString()).isEqualTo("SOFT");
        assertThatThrownBy(() -> service.delete(TARGET, ACTOR)).isInstanceOf(DomainException.class);
        assertThat(auditCount()).isEqualTo(1);
    }

    /** Xóa cứng theo cấu hình bỏ dòng profile nhưng vẫn chặn identity và giữ audit actor/target. */
    @Test
    void hardDeleteRetainsIndependentAuditAndDisabledIdentity() {
        properties.setUserSoftDelete(false);

        service.delete(TARGET, ACTOR);

        assertThat(countUser(TARGET)).isZero();
        assertThat(identities.get(TARGET).get("enabled")).isEqualTo(false);
        assertAudit("UserDeleted", TARGET);
        assertThat(auditPayload("UserDeleted").get("deletionMode").asString()).isEqualTo("HARD");
    }

    /** Xóa thất bại ở COMMIT phải khôi phục cả dòng còn hoạt động và cờ enabled Keycloak. */
    @Test
    void deferredDeleteCommitFailureRestoresProfileAndEnabledIdentity() {
        Map<String, Object> before = snapshot(TARGET);
        Map<String, Object> identityBefore = Map.copyOf(identities.get(TARGET));
        failAtCommit();

        assertThatThrownBy(() -> service.delete(TARGET, ACTOR)).isInstanceOf(RuntimeException.class);

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(identities.get(TARGET)).isEqualTo(identityBefore);
        assertThat(auditCount()).isZero();
    }

    /** Role thay thành công ghi bộ cũ/mới đúng actor và không phát thêm event khi gán lại cùng bộ. */
    @Test
    void roleReplacementCommitsPreviousAndCurrentRolesOnce() {
        service.assignRoles(TARGET, List.of("SELLER"), ACTOR);
        service.assignRoles(TARGET, List.of("SELLER"), ACTOR);

        assertThat(assigned(TARGET)).containsExactly(SELLER);
        assertAudit("UserRoleAssigned", TARGET);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(auditPayload("UserRoleAssigned").get("previousRoles").get(0).asString()).isEqualTo("BUYER");
        assertThat(auditPayload("UserRoleAssigned").get("roles").get(0).asString()).isEqualTo("SELLER");
    }

    /** Lỗi COMMIT audit sau khi đổi role phải trả lại đúng bộ role trước và không giữ event. */
    @Test
    void deferredRoleCommitFailureRestoresPreviousMappings() {
        failAtCommit();

        assertThatThrownBy(() -> service.assignRoles(TARGET, List.of("SELLER"), ACTOR))
                .isInstanceOf(RuntimeException.class);

        assertThat(assigned(TARGET)).containsExactly(BUYER);
        assertThat(auditCount()).isZero();
    }

    /** Mật khẩu thành công chỉ ghi metadata audit, tuyệt đối không ghi giá trị mật khẩu vào outbox. */
    @Test
    void passwordSuccessStoresOnlyActorTargetAndTimestamp() {
        service.changePassword(TARGET, "do-not-persist-this-password", ACTOR);

        verify(keycloak).resetPassword(TARGET, "do-not-persist-this-password");
        assertAudit("UserPasswordChanged", TARGET);
        assertThat(jdbc.queryForObject("select payload::text from outbox", String.class))
                .doesNotContain("do-not-persist-this-password", "credentials", "password_hash");
    }

    /** Provider từ chối mật khẩu phải rollback audit local đã ghi trước lời gọi remote. */
    @Test
    void rejectedPasswordRollsBackAudit() {
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_REJECTED)).when(keycloak)
                .resetPassword(TARGET, "rejected-password");

        assertThatThrownBy(() -> service.changePassword(TARGET, "rejected-password", ACTOR))
                .isInstanceOf(DomainException.class);

        assertThat(auditCount()).isZero();
    }

    /** Nếu audit không ghi được thì không được gọi bước đổi mật khẩu không thể hoàn tác. */
    @Test
    void passwordAuditWriteFailureStopsBeforeProviderMutation() {
        rejectOutboxWrites();

        assertThatThrownBy(() -> service.changePassword(TARGET, "safe-new-password", ACTOR))
                .isInstanceOf(RuntimeException.class);

        verify(keycloak, never()).resetPassword(any(), anyString());
        assertThat(auditCount()).isZero();
    }

    /** Unlock thành công gỡ trạng thái local LOCKED và ghi event chỉ sau xác minh khóa brute-force. */
    @Test
    void unlockCommitsActiveProfileAndAudit() {
        jdbc.update("update users set status='LOCKED' where id=?", TARGET);
        when(keycloak.isBruteForceLocked(TARGET)).thenReturn(true);

        service.unlock(TARGET, ACTOR);

        assertThat(snapshot(TARGET).get("status")).isEqualTo("ACTIVE");
        verify(keycloak).unlock(TARGET);
        assertAudit("UserUnlocked", TARGET);
    }

    /** Unlock provider thất bại phải giữ trạng thái LOCKED và rollback event local. */
    @Test
    void failedProviderUnlockRollsBackLocalStatusAndAudit() {
        jdbc.update("update users set status='LOCKED' where id=?", TARGET);
        Map<String, Object> before = snapshot(TARGET);
        when(keycloak.isBruteForceLocked(TARGET)).thenReturn(true);
        doThrow(new DomainException(UserErrorCode.USER_IDENTITY_PROVIDER_UNAVAILABLE)).when(keycloak).unlock(TARGET);

        assertThatThrownBy(() -> service.unlock(TARGET, ACTOR)).isInstanceOf(DomainException.class);

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(auditCount()).isZero();
    }

    /** Tài khoản không bị detector khóa phải trả 409 mà không đổi profile hay tạo audit. */
    @Test
    void unlockedAccountReturns409WithoutSideEffects() {
        Map<String, Object> before = snapshot(TARGET);
        when(keycloak.isBruteForceLocked(TARGET)).thenReturn(false);

        assertThatThrownBy(() -> service.unlock(TARGET, ACTOR)).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo("USER_ACCOUNT_NOT_LOCKED"));

        assertThat(snapshot(TARGET)).isEqualTo(before);
        assertThat(auditCount()).isZero();
        verify(keycloak, never()).unlock(any());
    }

    /** Dữ liệu đăng ký mới độc lập với fixture đã có để kiểm tra tạo và rollback. */
    private static AdminCreateUserRequest createRequest() {
        return new AdminCreateUserRequest("created@nexus.local", "0900000002", "new-test-password",
                "Created User", "ID-02", "FEMALE", "HCM", "2001-01-02", List.of("SELLER", "BUYER"));
    }

    /** Đọc bộ role remote hiện tại từ fixture có trạng thái, không mặc định thành công giả. */
    private List<KeycloakAdminClient.RealmRole> assigned(UUID id) {
        return roles.getOrDefault(id, List.of());
    }

    /** Đọc bằng connection riêng để chỉ quan sát dữ liệu đã commit sau khi service trả về. */
    private Map<String, Object> snapshot(UUID id) {
        return jdbc.queryForMap("select email,phone,full_name,address,status,deleted_at,version from users where id=?", id);
    }

    /** Đếm dòng profile kể cả dòng đã xóa mềm, không dựa vào bộ lọc repository. */
    private int countUser(UUID id) {
        return jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id);
    }

    /** Đếm outbox bằng SQL để xác nhận rollback audit cùng transaction profile. */
    private int auditCount() {
        return jdbc.queryForObject("select count(*) from outbox", Integer.class);
    }

    /** Đọc payload thực được tuần tự hóa qua OutboxWriter production. */
    private JsonNode auditPayload(String type) {
        return JSON.readTree(jdbc.queryForObject("select payload::text from outbox where event_type=?", String.class, type))
                .get("payload");
    }

    /** Kiểm tra tên event, actor, target, topic và trace đã lưu trong outbox thật. */
    private void assertAudit(String type, UUID id) {
        Map<String, Object> row = jdbc.queryForMap("select aggregate_type,aggregate_id,topic,trace_id,payload::text from outbox where event_type=?", type);
        assertThat(row.get("aggregate_type")).isEqualTo("User");
        assertThat(row.get("aggregate_id")).isEqualTo(id.toString());
        assertThat(row.get("topic")).isEqualTo("nexus.user.events");
        assertThat(row.get("trace_id")).isEqualTo("11223344556677889900aabbccddeeff");
        assertThat(auditPayload(type).get("actorId").asString()).isEqualTo(ACTOR.toString());
        assertThat(auditPayload(type).get("userId").asString()).isEqualTo(id.toString());
    }

    /** Tạo lỗi SQL ngay khi ghi outbox để kiểm tra rollback sau các bước profile/provider. */
    private void rejectOutboxWrites() {
        jdbc.execute("alter table outbox add constraint admin_test_outbox_failure check (event_type = 'NEVER_ALLOWED')");
    }

    /** Constraint trigger deferred khiến lỗi chỉ xuất hiện lúc PostgreSQL COMMIT, sau mọi flush. */
    private void failAtCommit() {
        jdbc.execute("""
                create or replace function admin_test_reject_commit() returns trigger language plpgsql as $$
                begin raise exception 'Forced admin transaction commit failure' using errcode='23514'; end; $$
                """);
        jdbc.execute("""
                create constraint trigger admin_test_commit_failure after insert on outbox
                deferrable initially deferred for each row execute function admin_test_reject_commit()
                """);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(PostgresAuditingTestConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    static class JpaConfig {
        /** Dùng PostgreSQL container mặc định, hoặc JDBC test riêng khi môi trường không có Docker. */
        @Bean
        DataSource dataSource() {
            String url = System.getProperty("admin.test.jdbc-url");
            if (url == null) return PostgresTestDatabase.migratedDataSource("admin_transactions");
            String separator = url.contains("?") ? "&" : "?";
            DataSource source = new DriverManagerDataSource(url + separator + "currentSchema=admin_transactions",
                    System.getProperty("admin.test.username", "admin211"), System.getProperty("admin.test.password", ""));
            Flyway.configure().dataSource(source).schemas("admin_transactions").defaultSchema("admin_transactions")
                    .load().migrate();
            return source;
        }

        /** Validate entity mapping với migration production thay vì tạo bảng bằng Hibernate. */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(User.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        /** Dùng transaction manager JPA thật để JDBC outbox cùng tham gia transaction profile. */
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        /** Tạo publisher production cùng OutboxWriter thật và trace ổn định cho các phép kiểm tra audit. */
        @Bean
        UserEventPublisher publisher(DataSource source, PlatformTransactionManager manager) {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("11223344556677889900aabbccddeeff");
            return new UserEventPublisher(new OutboxWriter(new JdbcTemplate(source), JSON, manager),
                    new EventEnvelopeFactory(tracer, Clock.fixed(NOW, ZoneOffset.UTC)));
        }
    }
}
