package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
import com.nexora.hotel.tenant.TenantEntity;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Servicio adicional contratado en una reservación, con nombre y precio congelados. {@code units} es lo que pidió
 * el huésped (ej. 2 traslados); {@code quantity} es lo que se cobra según la forma de cobro (unidades × noches...).
 */
@Entity
@Table(name = "reservation_services")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ReservationExtra extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "extra_service_id", nullable = false)
    private Long extraServiceId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_mode", nullable = false, length = 20)
    private ChargeMode chargeMode;

    @Column(nullable = false)
    private int units;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;
}
