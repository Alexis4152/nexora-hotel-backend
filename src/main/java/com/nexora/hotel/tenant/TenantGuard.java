package com.nexora.hotel.tenant;

import com.nexora.hotel.exception.ResourceNotFoundException;
import jakarta.persistence.PostLoad;

/**
 * Segunda línea de defensa del aislamiento entre hoteles: el filtro de {@code @TenantId} aplica a las
 * consultas, pero no a {@code findById} ni a las relaciones LAZY (se cargan por llave). Cualquier
 * entidad de otro hotel que llegue a cargarse corta la operación como "no encontrado", sin revelar
 * que existe. En ROOT no se revisa nada.
 */
public class TenantGuard {

    @PostLoad
    public void checkTenant(Object entity) {
        if (!(entity instanceof TenantEntity owned) || TenantContext.isRoot()) {
            return;
        }
        Long owner = owned.getHotelId();
        if (owner == null || owner != TenantContext.get()) {
            throw new ResourceNotFoundException("Recurso no encontrado");
        }
    }
}
