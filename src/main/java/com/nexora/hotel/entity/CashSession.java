package com.nexora.hotel.entity;

import com.nexora.hotel.enums.PaymentEnums.CashSessionStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Turno de caja de un usuario: fondo inicial, movimientos y corte. Una vez cerrada ya no cambia (trigger). */
@Entity
@Table(name = "cash_sessions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class CashSession extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CashSessionStatus status;

    @Column(name = "opening_float", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal openingFloat;

    @Column(name = "opening_notes", length = 500)
    private String openingNotes;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private Long closedBy;

    @Column(name = "expected_cash", precision = 12, scale = 2)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", precision = 12, scale = 2)
    private BigDecimal countedCash;

    @Column(precision = 12, scale = 2)
    private BigDecimal difference;

    @Column(name = "closing_notes", length = 500)
    private String closingNotes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> totals;
}
