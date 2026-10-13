package com.nexora.hotel.entity;

import com.nexora.hotel.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;

/** Cargo a la cuenta durante la estancia (importe final, con impuestos). No se borra: se anula con motivo. */
@Entity
@Table(name = "reservation_charges")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ReservationCharge extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    @Column(name = "service_id", updatable = false)
    private Long serviceId;

    @Column(nullable = false, length = 150, updatable = false)
    private String description;

    @Column(nullable = false, precision = 8, scale = 2, updatable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_reason", length = 300)
    private String voidReason;

    public boolean isVoided() {
        return voidedAt != null;
    }
}
