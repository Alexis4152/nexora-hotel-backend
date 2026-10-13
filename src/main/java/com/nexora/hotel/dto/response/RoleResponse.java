package com.nexora.hotel.dto.response;

import java.util.List;

/**
 * @param editable false para ADMIN (siempre tiene todos los permisos)
 */
public record RoleResponse(Long id, String code, String name, String description, boolean system, boolean editable,
                           long userCount, List<String> permissions) {
}
