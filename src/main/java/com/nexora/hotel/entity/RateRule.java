package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.AdjustmentType;
import com.nexora.hotel.enums.CatalogEnums.RateRuleType;
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
import java.time.LocalDate;

/**
 * Regla de tarifa. {@code roomTypeId} null = aplica a todos los tipos. {@code daysOfWeek} (WEEKEND) es una
 * lista separada por comas de {@code java.time.DayOfWeek} (ej. "FRIDAY,SATURDAY").
 */
@Entity
@Table(name = "rate_rules")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class RateRule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "room_type_id")
    private Long roomTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 15)
    private RateRuleType ruleType;

    @Column(name = "season_id")
    private Long seasonId;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "days_of_week", length = 60)
    private String daysOfWeek;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 12)
    private AdjustmentType adjustmentType;

    @Column(name = "adjustment_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal adjustmentValue;

    @Column(name = "min_nights")
    private Integer minNights;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
