package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.PenaltyType;
import com.nexora.hotel.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Versión de las políticas del hotel. Inmutable (la base rechaza UPDATE/DELETE): cambiar políticas crea
 * una versión nueva y las reservaciones existentes conservan la que aceptaron.
 */
@Entity
@Immutable
@Table(name = "hotel_policies")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class HotelPolicy extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int version;

    @Column(name = "free_cancel_hours", nullable = false)
    private int freeCancelHours;

    @Enumerated(EnumType.STRING)
    @Column(name = "late_cancel_penalty_type", nullable = false, length = 15)
    private PenaltyType lateCancelPenaltyType;

    @Column(name = "late_cancel_penalty_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateCancelPenaltyValue;

    @Column(name = "deposit_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal depositPercent;

    @Column(name = "pending_payment_expiry_minutes", nullable = false)
    private int pendingPaymentExpiryMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "no_show_penalty_type", nullable = false, length = 15)
    private PenaltyType noShowPenaltyType;

    @Column(name = "no_show_penalty_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal noShowPenaltyValue;

    @Column(name = "children_policy", length = 2000)
    private String childrenPolicy;

    @Column(name = "pets_allowed", nullable = false)
    private boolean petsAllowed;

    @Column(name = "pets_policy", length = 2000)
    private String petsPolicy;

    @Column(name = "refund_policy", length = 2000)
    private String refundPolicy;

    @Column(name = "date_change_policy", length = 2000)
    private String dateChangePolicy;

    @Column(columnDefinition = "text")
    private String terms;

    @Column(name = "privacy_notice", columnDefinition = "text")
    private String privacyNotice;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;
}
