package com.nexora.hotel.service.impl;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.request.SaveRoleRequest;
import com.nexora.hotel.dto.response.PermissionResponse;
import com.nexora.hotel.dto.response.RoleResponse;
import com.nexora.hotel.entity.Permission;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.PermissionRepository;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.service.RoleAdminService;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reglas:
 * <ul>
 *   <li>ADMIN no se edita: siempre tiene todo el catálogo. GUEST no se muestra ni edita (es del portal).</li>
 *   <li>Permisos reservados: nunca en otro rol que no sea ADMIN.</li>
 *   <li>Solo se agregan a un rol permisos que quien edita ya tiene (anti-escalación).</li>
 *   <li>Al guardar, se invalida la caché de permisos de todos los usuarios del rol: aplica en su siguiente request.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RoleAdminServiceImpl implements RoleAdminService {

    private static final Set<String> RESERVED_CODES = Set.of(Role.ADMIN, Role.RECEPTIONIST, Role.HOUSEKEEPER, Role.GUEST, Role.SUPER_ADMIN);

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PermissionResolver permissionResolver;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(readOnly = true)
    public List<PermissionResponse> catalog() {
        return permissionRepository.findAllByOrderBySortOrderAsc().stream()
                .map(p -> new PermissionResponse(p.getCode(), p.getModule(), p.getAction(), p.getDescription(),
                        p.isReserved(), p.isSensitive()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        Map<Long, Long> counts = userCounts();
        return roleRepository.findByActiveTrueOrderByNameAsc().stream()
                .filter(r -> !Role.GUEST.equals(r.getCode()))
                .sorted(Comparator.comparing((Role r) -> !r.isSystem()).thenComparing(Role::getName))
                .map(r -> toResponse(r, counts.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional
    public RoleResponse create(SaveRoleRequest request) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        String code = uniqueCode(request.name());
        Set<Permission> permissions = validatePermissions(actor, Set.of(), request.permissions());
        Role role = roleRepository.save(Role.builder()
                .code(code).name(request.name().trim()).description(blankToNull(request.description()))
                .system(false).permissions(permissions).build());
        auditService.record(AuditEntry.success("ROLE_CREATED", "Role", role.getId())
                .after(snapshot(role)).build());
        return toResponse(role, 0);
    }

    @Override
    @Transactional
    public RoleResponse update(Long id, SaveRoleRequest request) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Role role = findEditable(id);
        Map<String, Object> before = snapshot(role);

        Set<String> current = codes(role.getPermissions());
        Set<Permission> permissions = validatePermissions(actor, current, request.permissions());
        if (!role.isSystem()) {
            role.setName(request.name().trim());
        }
        role.setDescription(blankToNull(request.description()));
        role.getPermissions().clear();
        role.getPermissions().addAll(permissions);
        roleRepository.save(role);
        permissionResolver.bumpUsersOfRole(role.getId());

        auditService.record(AuditEntry.success("ROLE_PERMISSIONS_CHANGED", "Role", id)
                .before(before).after(snapshot(role)).build());
        return toResponse(role, userCounts().getOrDefault(id, 0L));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Role role = findEditable(id);
        if (role.isSystem()) {
            throw new BusinessException("Los roles del sistema no se pueden eliminar");
        }
        if (userCounts().getOrDefault(id, 0L) > 0) {
            throw new ConflictException("Hay usuarios con este rol. Reasígnalos antes de eliminarlo.");
        }
        Map<String, Object> before = snapshot(role);
        roleRepository.delete(role);
        auditService.record(AuditEntry.success("ROLE_DELETED", "Role", id).before(before).build());
    }

    private Role findEditable(Long id) {
        Role role = roleRepository.findById(id).filter(Role::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Rol no encontrado"));
        if (Role.ADMIN.equals(role.getCode())) {
            throw new BusinessException("El rol Administrador siempre tiene todos los permisos y no se puede editar");
        }
        if (Role.GUEST.equals(role.getCode())) {
            throw new ResourceNotFoundException("Rol no encontrado");
        }
        return role;
    }

    /** Valida códigos; los permisos que se AGREGAN deben estar en manos de quien edita. */
    private Set<Permission> validatePermissions(AuthPrincipal actor, Set<String> current, Set<String> requested) {
        List<Permission> found = permissionRepository.findByCodeIn(requested);
        if (found.size() != requested.size()) {
            throw new BusinessException("Hay permisos desconocidos en la solicitud");
        }
        for (Permission p : found) {
            if (p.isReserved()) {
                throw new AccessDeniedException("El permiso '" + p.getDescription() + "' está reservado al administrador");
            }
            if (!current.contains(p.getCode()) && !actor.hasPermission(p.getCode())) {
                throw new AccessDeniedException("No puedes otorgar un permiso que no tienes: " + p.getDescription());
            }
        }
        return new HashSet<>(found);
    }

    private String uniqueCode(String name) {
        String base = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "ROL_" + base;
        }
        base = base.length() > 34 ? base.substring(0, 34) : base;
        if (RESERVED_CODES.contains(base)) {
            throw new ConflictException("Ese nombre corresponde a un rol del sistema");
        }
        String code = base;
        int suffix = 2;
        while (roleRepository.existsByCode(code)) {
            code = base + "_" + suffix++;
        }
        return code;
    }

    private Map<Long, Long> userCounts() {
        long hotelId = TenantContext.requireHotelId();
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT ur.role_id, count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id
                WHERE u.hotel_id = ? AND u.deleted_at IS NULL GROUP BY ur.role_id""",
                rs -> {
                    counts.put(rs.getLong(1), rs.getLong(2));
                }, hotelId);
        return counts;
    }

    private RoleResponse toResponse(Role role, long users) {
        List<String> permissions = Role.ADMIN.equals(role.getCode())
                ? permissionResolver.allPermissions().stream().sorted().toList()
                : codes(role.getPermissions()).stream().sorted().toList();
        return new RoleResponse(role.getId(), role.getCode(), role.getName(), role.getDescription(), role.isSystem(),
                !Role.ADMIN.equals(role.getCode()), users, permissions);
    }

    private static Map<String, Object> snapshot(Role role) {
        return Map.of("code", role.getCode(), "name", role.getName(),
                "permissions", codes(role.getPermissions()).stream().sorted().toList());
    }

    private static Set<String> codes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::getCode).collect(Collectors.toSet());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
