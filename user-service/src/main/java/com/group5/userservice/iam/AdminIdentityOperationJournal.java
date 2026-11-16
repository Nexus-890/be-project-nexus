package com.group5.userservice.iam;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Nhật ký độc lập cho thao tác Keycloak không thể hoàn tác; không nhận hoặc lưu credential. */
@Component
public class AdminIdentityOperationJournal {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JdbcTemplate independentJdbc;
    private final TransactionTemplate independent;
    private final TransactionTemplate mandatory;

    /** Connection riêng không mượn từ pool đang giữ transaction profile, tránh pool starvation ở REQUIRES_NEW. */
    @Autowired
    public AdminIdentityOperationJournal(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager,
                                         DataSourceProperties properties) {
        this(jdbc, clock, manager, properties.initializeDataSourceBuilder()
                .type(DriverManagerDataSource.class).build());
    }

    AdminIdentityOperationJournal(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager,
                                  DataSource independentSource) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.independentJdbc = new JdbcTemplate(independentSource);
        independent = new TransactionTemplate(new DataSourceTransactionManager(independentSource));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** Commit ý định trước HTTP; lỗi ở đây phải ngăn lời gọi remote. Không FK để tránh khóa chéo transaction. */
    public UUID prepare(String eventType, UUID userId, UUID actorId, long profileVersion) {
        UUID operationId = UUID.randomUUID();
        independent.executeWithoutResult(status -> independentJdbc.update("""
                insert into admin_identity_operations
                    (operation_id,user_id,actor_id,event_type,profile_version,state,created_at)
                values (?,?,?,?,?,'PENDING',?)
                """, operationId, userId, actorId, eventType, profileVersion, Timestamp.from(clock.instant())));
        return operationId;
    }

    /** Chỉ response HTTP thành công mới được xác nhận; timeout/crash giữ PENDING cho đối soát thủ công. */
    public void remoteSucceeded(UUID operationId) {
        independent.executeWithoutResult(status -> requireOne(independentJdbc.update("""
                update admin_identity_operations set state='REMOTE_SUCCEEDED',remote_confirmed_at=?
                where operation_id=? and state='PENDING'
                """, Timestamp.from(clock.instant()), operationId)));
    }

    /** Cùng transaction profile/outbox: COMMIT mơ hồ vẫn đọc được COMPLETED hoặc REMOTE_SUCCEEDED. */
    public void complete(UUID operationId) {
        mandatory.executeWithoutResult(status -> requireOne(jdbc.update("""
                update admin_identity_operations set state='COMPLETED',completed_at=?
                where operation_id=? and state='REMOTE_SUCCEEDED'
                """, Timestamp.from(clock.instant()), operationId)));
    }

    /** Snapshot không khóa; worker phải khóa user trước journal để giữ cùng thứ tự với request gốc. */
    public List<Operation> confirmed() {
        return jdbc.query("""
                select operation_id,user_id,actor_id,event_type,profile_version,remote_confirmed_at
                from admin_identity_operations where state='REMOTE_SUCCEEDED'
                order by created_at,operation_id limit 100
                """, (row, index) -> new Operation(row.getObject("operation_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("actor_id", UUID.class),
                row.getString("event_type"), row.getLong("profile_version"),
                row.getTimestamp("remote_confirmed_at").toInstant()));
    }

    /** Recheck sau khi khóa profile; một worker/instance khác có thể đã hoàn tất transaction. */
    public Optional<Operation> lockConfirmed(UUID operationId) {
        return mandatory.execute(status -> jdbc.query("""
                select operation_id,user_id,actor_id,event_type,profile_version,remote_confirmed_at
                from admin_identity_operations where operation_id=? and state='REMOTE_SUCCEEDED'
                for update
                """, (row, index) -> new Operation(row.getObject("operation_id", UUID.class),
                row.getObject("user_id", UUID.class), row.getObject("actor_id", UUID.class),
                row.getString("event_type"), row.getLong("profile_version"),
                row.getTimestamp("remote_confirmed_at").toInstant()), operationId).stream().findFirst());
    }

    /** Profile đã đổi/xóa thì ghi audit remote đã xảy ra nhưng để người vận hành xác nhận local. */
    public void requireReview(UUID operationId) {
        mandatory.executeWithoutResult(status -> requireOne(jdbc.update("""
                update admin_identity_operations set state='REVIEW_REQUIRED'
                where operation_id=? and state='REMOTE_SUCCEEDED'
                """, operationId)));
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Identity operation state changed concurrently");
    }

    public record Operation(UUID operationId, UUID userId, UUID actorId, String eventType,
                            long profileVersion, Instant remoteConfirmedAt) { }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling { }
}
