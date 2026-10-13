package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Permiso del catálogo global ({@code MODULO:ACCION}). Solo lo crean las migraciones de Flyway; la
 * aplicación nunca lo modifica (por eso {@link Immutable}). Ver docs/03-PERMISOS.md.
 */
@Entity
@Immutable
@Table(name = "permissions")
@Getter
@NoArgsConstructor
public class Permission {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String code;

    @Column(nullable = false, length = 30)
    private String module;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(nullable = false, length = 200)
    private String description;

    /** Solo asignable al rol de sistema ADMIN. */
    @Column(nullable = false)
    private boolean reserved;

    /** Otorgarlo exige confirmación en la UI y queda en auditoría como cambio sensible. */
    @Column(nullable = false)
    private boolean sensitive;

    @Column(name = "default_receptionist", nullable = false)
    private boolean defaultReceptionist;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
}
