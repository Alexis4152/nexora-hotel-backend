package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** Amenidad (Wi-Fi, aire acondicionado, vista al mar...). El ícono es un nombre de la librería de íconos del frontend. */
@Entity
@Table(name = "amenities")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Amenity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    @Builder.Default
    private String icon = "sparkles";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
