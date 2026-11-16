package com.group5.userservice.seller;

import com.group5.common_core.persistence.AuditedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "seller")
@Getter
@Setter
@NoArgsConstructor
public class Seller extends AuditedEntity {
    @Id @Column(name = "user_id")
    private UUID userId;
    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;
    @Column(name = "contact_phone", nullable = false, length = 20)
    private String contactPhone;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "seller_status")
    private SellerStatus status;
    @Column(name = "application_note", columnDefinition = "text")
    private String applicationNote;
    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;
    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt;
    @Column(name = "reviewed_by")
    private UUID reviewedBy;
    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;
    @Version @Column(nullable = false)
    private Long version;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
