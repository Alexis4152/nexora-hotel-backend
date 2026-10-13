package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.MaintenanceStatus;
import com.nexora.hotel.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/** Bloqueo por mantenimiento de una habitación en un periodo [startsOn, endsOn). */
@Entity
@Table(name = "maintenance_records")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class MaintenanceRecord extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MaintenanceStatus status = MaintenanceStatus.OPEN;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private Long releasedBy;

    @Column(name = "release_notes", length = 500)
    private String releaseNotes;
}
