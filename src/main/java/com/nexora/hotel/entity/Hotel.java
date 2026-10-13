package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Hotel cliente de la plataforma (el tenant). Todo dato de negocio pertenece a uno solo vía
 * {@code hotel_id}. Sus huéspedes y personal entran por {@code <frontend>/h/<slug>}. Lo administra
 * solo el SUPER_ADMIN de Nexora.
 */
@Entity
@Table(name = "hotels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Hotel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificador del enlace público: minúsculas, números y guiones. */
    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 150)
    private String name;

    /** Zona IANA (ej. America/Cancun): toda regla de horarios y políticas se evalúa en esta zona. */
    @Column(nullable = false, length = 60)
    @Builder.Default
    private String timezone = "America/Mexico_City";

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "MXN";

    @Column(name = "custom_domain", length = 255)
    private String customDomain;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }
}
