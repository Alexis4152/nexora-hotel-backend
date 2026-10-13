package com.nexora.hotel.security;

import java.util.Set;

/**
 * Principal inmutable de cada request autenticada. Se arma a partir del usuario y sus permisos
 * efectivos (roles + overrides) y es lo único que ven las capas superiores de quién está operando.
 *
 * @param userId      id del usuario
 * @param hotelId     hotel al que pertenece; null para el SUPER_ADMIN (opera en ROOT)
 * @param email       correo (para auditoría)
 * @param roles       códigos de rol (ADMIN, RECEPTIONIST, GUEST, personalizados)
 * @param permissions códigos de permiso efectivos ({@code MODULO:ACCION})
 */
public record AuthPrincipal(Long userId, Long hotelId, String email, Set<String> roles, Set<String> permissions) {

    public boolean hasPermission(String code) {
        return permissions.contains(code);
    }

    public boolean hasRole(String code) {
        return roles.contains(code);
    }

    public boolean isPlatform() {
        return hotelId == null;
    }
}
