package com.nexora.hotel.entity;

import com.nexora.hotel.enums.PaymentEnums.CashMovementType;
import com.nexora.hotel.enums.PaymentEnums.Direction;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
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
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/** Movimiento de caja. Solo inserción y solo en una caja abierta (triggers de V7). */
@Entity
@Immutable
@Table(name = "cash_movements")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class CashMovement extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CashMovementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Direction direction;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(length = 300)
    private String reason;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BigDecimal signed() {
        return direction == Direction.IN ? amount : amount.negate();
    }
}
