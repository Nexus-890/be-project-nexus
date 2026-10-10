package com.group5.userservice.reputation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_reputations")
@Getter
@Setter
@NoArgsConstructor
public class UserReputation {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false)
    private short score = 50;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20)
    private TrustLevel trustLevel = TrustLevel.NEW;

    @Column(name = "total_transaction", nullable = false)
    private int totalTransaction;

    @Column(name = "successful_transaction", nullable = false)
    private int successfulTransaction;

    @Column(name = "failed_transaction", nullable = false)
    private int failedTransaction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;
}
