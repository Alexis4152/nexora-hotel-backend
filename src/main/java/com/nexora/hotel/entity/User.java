package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Cuenta con acceso al sistema: personal del hotel (ADMIN, RECEPTIONIST, roles personalizados),
 * huésped registrado (GUEST) o usuario de plataforma (SUPER_ADMIN, sin hotel). El correo es único
 * por hotel; el mismo correo puede tener cuenta en hoteles distintos.
 * <p>
 * No es el principal de Spring Security: en cada request se usa un {@code AuthPrincipal} inmutable,
 * así ninguna capa trabaja con una entidad desconectada de su sesión de Hibernate.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 20)
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Obliga a pasar por el cambio de contraseña (alta con contraseña temporal). */
    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Cambia cada vez que cambian sus roles o permisos directos: invalida su caché de permisos. */
    @Column(name = "permissions_version", nullable = false)
    @Builder.Default
    private int permissionsVersion = 0;

    /** Solo los huéspedes (GUEST) necesitan verificar su correo para iniciar sesión. */
    @Column(name = "phone_country_code", length = 4)
    private String phoneCountryCode;

    @Column(name = "email_verified_at")
    @Builder.Default
    private Instant emailVerifiedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    public String fullName() {
        return firstName + " " + lastName;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    @PrePersist
    @PreUpdate
    void normalize() {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }
}
