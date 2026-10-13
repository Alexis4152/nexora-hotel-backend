package com.nexora.hotel.entity;

import com.nexora.hotel.enums.ReservationEnums.ReservationSource;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reservación. Los importes y la política aceptada quedan congelados: un cambio de tarifas o políticas no los
 * altera. Solo un cambio explícito (fechas, habitación, huéspedes) recalcula y crea una nueva revisión de precio.
 */
@Entity
@Table(name = "reservations")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Reservation extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String folio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "contact_name", nullable = false, length = 201)
    private String contactName;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "contact_phone", length = 25)
    private String contactPhone;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private int nights;

    @Column(nullable = false)
    private int adults;

    @Column(nullable = false)
    private int children;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ReservationSource source;

    @Column(name = "room_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal roomSubtotal;

    @Column(name = "extra_guests_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal extraGuestsTotal = BigDecimal.ZERO;

    @Column(name = "services_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal servicesTotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "MXN";

    @Column(name = "penalty_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    @Column(name = "price_revision", nullable = false)
    @Builder.Default
    private int priceRevision = 1;

    @Column(name = "policy_version_id")
    private Long policyVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> policySnapshot;

    @Column(name = "accepted_terms_at")
    private Instant acceptedTermsAt;

    @Column(name = "special_requests", length = 1000)
    private String specialRequests;

    @Column(name = "internal_notes", length = 2000)
    private String internalNotes;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "charges_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal chargesTotal = BigDecimal.ZERO;

    /** Neto cobrado (pagos menos reembolsos y reversos). Se actualiza con la reservación bloqueada. */
    @Column(name = "paid_total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal paidTotal = BigDecimal.ZERO;

    @Column(name = "deposit_required", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal depositRequired = BigDecimal.ZERO;

    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "checked_in_by")
    private Long checkedInBy;

    @Column(name = "check_in_notes", length = 1000)
    private String checkInNotes;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    @Column(name = "checked_out_by")
    private Long checkedOutBy;

    @Column(name = "check_out_notes", length = 1000)
    private String checkOutNotes;

    @Column(name = "no_show_at")
    private Instant noShowAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReservationRoom> rooms = new ArrayList<>();

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReservationExtra> services = new ArrayList<>();

    /** Lo que el huésped debe: el total (o solo la penalización si se canceló o no llegó) más los cargos a la cuenta. */
    public BigDecimal amountDue() {
        BigDecimal base = switch (status) {
            case CANCELLED, NO_SHOW -> penaltyAmount;
            case EXPIRED -> BigDecimal.ZERO;
            default -> total;
        };
        return base.add(chargesTotal);
    }

    /** Positivo: falta por cobrar. Negativo: saldo a favor del huésped (reembolso pendiente). */
    public BigDecimal balance() {
        return amountDue().subtract(paidTotal);
    }
}
