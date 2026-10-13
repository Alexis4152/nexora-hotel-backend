package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.SeasonKind;
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

import java.time.LocalDate;

/** Temporada (alta, baja, festiva) en un rango de fechas inclusivo. Las reglas de tarifa SEASON la referencian. */
@Entity
@Table(name = "seasons")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Season extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeasonKind kind;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public boolean contains(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }
}
