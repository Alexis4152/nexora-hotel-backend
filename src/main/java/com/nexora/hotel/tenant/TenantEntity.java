package com.nexora.hotel.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.TenantId;

/**
 * Base de todo dato que pertenece a un hotel. {@code @TenantId} hace que Hibernate:
 * <ul>
 *   <li>filtre cada consulta por el hotel actual ({@link TenantContext}), y</li>
 *   <li>llene {@code hotelId} al insertar con el hotel actual (en ROOT respeta el valor de la entidad).</li>
 * </ul>
 * {@link TenantGuard} cubre lo que el filtro no ve: cargas por id y relaciones LAZY.
 * {@code hotelId} solo es null en datos de plataforma (usuario/rol SUPER_ADMIN, auditoría de plataforma).
 */
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
@EntityListeners(TenantGuard.class)
public abstract class TenantEntity {

    @TenantId
    @Column(name = "hotel_id", updatable = false)
    private Long hotelId;
}
