package com.nexora.hotel.security;

import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calcula los permisos efectivos de un usuario y los guarda en caché por {@code permissions_version}.
 * <p>
 * Reglas (docs/03-PERMISOS.md):
 * <ul>
 *   <li>Rol ADMIN: todos los permisos del catálogo, siempre. No se puede recortar (evita que un hotel
 *       se quede sin quién lo administre).</li>
 *   <li>Otros roles: unión de los permisos de sus roles activos + overrides GRANT − overrides DENY.
 *       Los permisos reservados nunca se otorgan fuera del ADMIN, aunque la BD los tuviera.</li>
 *   <li>SUPER_ADMIN: ningún permiso de hotel; solo opera la plataforma.</li>
 * </ul>
 * Invalidación: al cambiar roles/permisos se incrementa {@code users.permissions_version} de los
 * afectados ({@link #bumpUsersOfRole}, {@link #bumpUser}); el siguiente request ve otra versión y
 * recalcula. No depende de memoria compartida, así que funciona también con varias instancias.
 */
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private final JdbcTemplate jdbc;

    private record Cached(int version, Set<String> roles, Set<String> permissions) {
    }

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private volatile Set<String> allPermissions;
    private volatile Set<String> reservedPermissions;

    public AuthPrincipal principalFor(User user) {
        Cached cached = cache.get(user.getId());
        if (cached == null || cached.version() != user.getPermissionsVersion()) {
            cached = compute(user);
            cache.put(user.getId(), cached);
        }
        return new AuthPrincipal(user.getId(), user.getHotelId(), user.getEmail(), cached.roles(), cached.permissions());
    }

    private Cached compute(User user) {
        Set<String> roles = Set.copyOf(jdbc.queryForList("""
                SELECT r.code FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.active""", String.class, user.getId()));

        if (roles.contains(Role.SUPER_ADMIN)) {
            return new Cached(user.getPermissionsVersion(), roles, Set.of());
        }
        if (roles.contains(Role.ADMIN)) {
            return new Cached(user.getPermissionsVersion(), roles, allPermissions());
        }

        Set<String> permissions = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.code FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id AND r.active
                JOIN role_permissions rp ON rp.role_id = r.id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE ur.user_id = ?""", String.class, user.getId()));

        List<Map<String, Object>> overrides = jdbc.queryForList("""
                SELECT p.code, o.effect FROM user_permission_overrides o
                JOIN permissions p ON p.id = o.permission_id WHERE o.user_id = ?""", user.getId());
        overrides.stream().filter(o -> "GRANT".equals(o.get("effect")))
                .forEach(o -> permissions.add((String) o.get("code")));
        overrides.stream().filter(o -> "DENY".equals(o.get("effect")))
                .forEach(o -> permissions.remove((String) o.get("code")));

        permissions.removeAll(reservedPermissions());
        return new Cached(user.getPermissionsVersion(), roles, Set.copyOf(permissions));
    }

    /** Invalida los permisos de todos los usuarios con ese rol (se llama dentro de la transacción del cambio). */
    public void bumpUsersOfRole(Long roleId) {
        jdbc.update("""
                UPDATE users SET permissions_version = permissions_version + 1
                WHERE id IN (SELECT user_id FROM user_roles WHERE role_id = ?)""", roleId);
    }

    public void bumpUser(Long userId) {
        jdbc.update("UPDATE users SET permissions_version = permissions_version + 1 WHERE id = ?", userId);
    }

    public Set<String> allPermissions() {
        if (allPermissions == null) {
            allPermissions = Set.copyOf(jdbc.queryForList("SELECT code FROM permissions", String.class));
        }
        return allPermissions;
    }

    public Set<String> reservedPermissions() {
        if (reservedPermissions == null) {
            reservedPermissions = Set.copyOf(jdbc.queryForList("SELECT code FROM permissions WHERE reserved", String.class));
        }
        return reservedPermissions;
    }
}
