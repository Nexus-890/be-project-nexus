package com.group5.shipmentservice.shipping.entity;

import com.group5.shipmentservice.shared.persistence.AuditedEntity;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Immutable
@Table(name = "shipment_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShipmentHistory extends AuditedEntity {

    @Id
    @ColumnDefault("uuidv7()")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "shipment_status", nullable = false, columnDefinition = "shipment_status")
    private ShipmentStatus shipmentStatus;

    @Column(name = "description", length = 255)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "carrier_event_id", length = 160)
    private String carrierEventId;

    @Column(name = "source", nullable = false, length = 30)
    private String source = "SYSTEM";

    @Column(name = "changed_by")
    private UUID changedBy;
}
