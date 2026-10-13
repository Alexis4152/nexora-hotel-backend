package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
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

/** Servicio adicional (desayuno, estacionamiento, traslado...) con su forma de cobro. */
@Entity
@Table(name = "extra_services")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ExtraService extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_mode", nullable = false, length = 20)
    private ChargeMode chargeMode;

    @Column(name = "bookable_online", nullable = false)
    @Builder.Default
    private boolean bookableOnline = true;

    @Column(nullable = false, length = 40)
    @Builder.Default
    private String icon = "concierge-bell";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
