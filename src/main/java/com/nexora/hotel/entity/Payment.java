package com.nexora.hotel.entity;

import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Movimiento del libro de pagos de una reservación. Nunca se borra y, una vez fuera de PENDIENTE, no cambia
 * (lo garantiza un trigger). Las correcciones son movimientos nuevos (REVERSAL / REFUND) ligados al original.
 * No se guardan números de tarjeta ni CVV: solo la marca y los últimos 4 dígitos que reporta la pasarela o el voucher.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Payment extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "MXN";

    @Column(length = 100)
    private String reference;

    @Column(length = 500)
    private String notes;

    @Column(name = "related_payment_id", updatable = false)
    private Long relatedPaymentId;

    @Column(name = "cash_session_id")
    private Long cashSessionId;

    @Column(name = "idempotency_key", length = 80, updatable = false)
    private String idempotencyKey;

    @Column(length = 20)
    private String provider;

    @Column(name = "provider_charge_id", length = 80)
    private String providerChargeId;

    @Column(name = "provider_order_id", length = 80)
    private String providerOrderId;

    @Column(name = "provider_status", length = 30)
    private String providerStatus;

    @Column(name = "checkout_url", length = 600)
    private String checkoutUrl;

    @Column(name = "public_ref", length = 64)
    private String publicRef;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "card_brand", length = 30)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Importe con signo sobre lo pagado: positivo si entra, negativo si sale. */
    public BigDecimal signedAmount() {
        return kind.isOutflow() ? amount.negate() : amount;
    }
}
