package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.TaxAppliesTo;
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

/** Impuesto (IVA, ISH). Si {@code includedInPrice}, solo se desglosa; si no, se suma al subtotal. */
@Entity
@Table(name = "tax_rates")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class TaxRate extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal percent;

    @Enumerated(EnumType.STRING)
    @Column(name = "applies_to", nullable = false, length = 10)
    private TaxAppliesTo appliesTo;

    @Column(name = "included_in_price", nullable = false)
    private boolean includedInPrice;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
