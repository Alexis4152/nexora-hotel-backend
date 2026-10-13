package com.nexora.hotel.service.impl;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.request.CreateUserRequest;
import com.nexora.hotel.dto.request.UpdateUserRequest;
import com.nexora.hotel.dto.request.UserOverridesRequest;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;
import com.nexora.hotel.dto.response.UserDetailResponse;
import com.nexora.hotel.dto.response.UserResponse;
import com.nexora.hotel.entity.Permission;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.entity.UserPermissionOverride;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.mapper.UserMapper;
import com.nexora.hotel.repository.PermissionRepository;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.UserPermissionOverrideRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.service.RefreshTokenService;
import com.nexora.hotel.service.UserAdminService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reglas de seguridad (docs/03-PERMISOS.md):
 * <ul>
 *   <li>Nadie modifica sus propios roles, permisos ni estado.</li>
 *   <li>Solo un ADMIN asigna el rol ADMIN; cualquier otro rol solo si quien asigna ya tiene todos sus permisos.</li>
 *   <li>El último administrador activo del hotel no se desactiva ni pierde el rol ADMIN.</li>
 *   <li>Overrides: nunca permisos reservados; GRANT solo de permisos que quien otorga tiene.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserAdminServiceImpl implements UserAdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionOverrideRepository overrideRepository;
    private final PermissionResolver permissionResolver;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String query, Boolean active, int page, int size) {
        Specification<User> spec = staffOnly().and(notDeleted());
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("firstName")), like),
                    cb.like(cb.lower(root.get("lastName")), like)));
        }
        if (active != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
        }
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by("firstName", "lastName"));
        return PageResponse.of(userRepository.findAll(spec, pageable), userMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetailResponse get(Long id) {
        return detail(findStaff(id));
    }

    @Override
    @Transactional
    public TemporaryPasswordResponse create(CreateUserRequest request) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Ya existe un usuario con ese correo en este hotel");
        }
        Set<Role> roles = assignableRoles(actor, request.roleIds());
        String temporary = SecureTokens.temporaryPassword();
        User user = userRepository.save(User.builder()
                .email(email)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .phone(blankToNull(request.phone()))
                .passwordHash(passwordEncoder.encode(temporary))
                .mustChangePassword(true)
                .roles(new HashSet<>(roles))
                .build());
        auditService.record(AuditEntry.success("USER_CREATED", "User", user.getId())
                .after(snapshot(user)).build());
        return new TemporaryPasswordResponse(user.getEmail(), temporary);
    }

    @Override
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        User user = findStaff(id);
        Map<String, Object> before = snapshot(user);
        Set<Role> roles = assignableRoles(actor, request.roleIds());

        boolean rolesChanged = !roleIds(user.getRoles()).equals(roleIds(roles));
        if (rolesChanged) {
            requireNotSelf(actor, user, "No puedes cambiar tus propios roles");
            boolean losesAdmin = hasRole(user, Role.ADMIN) && roles.stream().noneMatch(r -> Role.ADMIN.equals(r.getCode()));
            if (losesAdmin) {
                requireAdmin(actor, "Solo un administrador puede quitar el rol de administrador");
                requireAnotherActiveAdmin(user);
            }
            user.getRoles().clear();
            user.getRoles().addAll(roles);
            permissionResolver.bumpUser(user.getId());
        }
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPhone(blankToNull(request.phone()));
        userRepository.save(user);
        auditService.record(AuditEntry.success(rolesChanged ? "USER_ROLES_CHANGED" : "USER_UPDATED", "User", id)
                .before(before).after(snapshot(user)).build());
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse setActive(Long id, boolean active) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        User user = findStaff(id);
        requireNotSelf(actor, user, "No puedes desactivar tu propia cuenta");
        if (hasRole(user, Role.ADMIN)) {
            requireAdmin(actor, "Solo un administrador puede activar o desactivar a otro administrador");
            if (!active) {
                requireAnotherActiveAdmin(user);
            }
        }
        if (user.isActive() != active) {
            user.setActive(active);
            if (!active) {
                refreshTokenService.revokeAllForUser(user.getId());
            }
            auditService.record(AuditEntry.success(active ? "USER_ACTIVATED" : "USER_DEACTIVATED", "User", id).build());
        }
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public TemporaryPasswordResponse resetPassword(Long id) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        User user = findStaff(id);
        requireNotSelf(actor, user, "Para cambiar tu contraseña usa la opción de tu perfil");
        if (hasRole(user, Role.ADMIN)) {
            requireAdmin(actor, "Solo un administrador puede restablecer la contraseña de otro administrador");
        }
        String temporary = SecureTokens.temporaryPassword();
        user.setPasswordHash(passwordEncoder.encode(temporary));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        refreshTokenService.revokeAllForUser(user.getId());
        auditService.record(AuditEntry.success("USER_PASSWORD_RESET", "User", id).build());
        return new TemporaryPasswordResponse(user.getEmail(), temporary);
    }

    @Override
    @Transactional
    public UserDetailResponse setOverrides(Long id, UserOverridesRequest request) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        User user = findStaff(id);
        requireNotSelf(actor, user, "No puedes modificar tus propios permisos");
        if (hasRole(user, Role.ADMIN)) {
            throw new BusinessException("Los administradores siempre tienen todos los permisos; no admiten ajustes");
        }

        Map<String, UserOverridesRequest.Item> requested = new LinkedHashMap<>();
        request.overrides().forEach(item -> requested.put(item.code(), item));
        Map<String, Permission> catalog = permissionRepository.findByCodeIn(requested.keySet()).stream()
                .collect(Collectors.toMap(Permission::getCode, p -> p));
        for (UserOverridesRequest.Item item : requested.values()) {
            Permission permission = catalog.get(item.code());
            if (permission == null) {
                throw new BusinessException("Permiso desconocido: " + item.code());
            }
            if (permission.isReserved()) {
                throw new AccessDeniedException("El permiso " + item.code() + " está reservado al administrador");
            }
            if (!actor.hasPermission(item.code())) {
                throw new AccessDeniedException("No puedes otorgar ni retirar un permiso que no tienes: " + item.code());
            }
        }

        List<UserPermissionOverride> previous = overrideRepository.findByUserIdWithPermission(id);
        Map<String, Object> before = Map.of("overrides", previous.stream()
                .map(o -> o.getPermission().getCode() + "=" + o.getEffect()).sorted().toList());
        overrideRepository.deleteByUserId(id);
        entityManager.flush();
        for (UserOverridesRequest.Item item : requested.values()) {
            overrideRepository.save(UserPermissionOverride.builder()
                    .userId(id).permission(catalog.get(item.code())).effect(item.effect())
                    .reason(request.reason()).createdBy(actor.userId()).build());
        }
        permissionResolver.bumpUser(id);
        auditService.record(AuditEntry.success("USER_PERMISSIONS_CHANGED", "User", id).before(before)
                .after(Map.of("overrides", requested.values().stream().map(i -> i.code() + "=" + i.effect()).sorted().toList()))
                .detail(request.reason()).build());
        entityManager.flush();
        entityManager.refresh(user);
        return detail(user);
    }

    // ── Reglas ────────────────────────────────────────────────────────────────────────

    private Set<Role> assignableRoles(AuthPrincipal actor, Set<Long> roleIds) {
        List<Role> roles = roleRepository.findAllById(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new BusinessException("Alguno de los roles no existe");
        }
        for (Role role : roles) {
            if (!role.isActive() || Role.GUEST.equals(role.getCode())) {
                throw new BusinessException("El rol " + role.getName() + " no se puede asignar al personal");
            }
            if (Role.ADMIN.equals(role.getCode())) {
                requireAdmin(actor, "Solo un administrador puede asignar el rol de administrador");
            } else {
                Set<String> missing = role.getPermissions().stream().map(Permission::getCode)
                        .filter(code -> !actor.hasPermission(code)).collect(Collectors.toSet());
                if (!missing.isEmpty()) {
                    throw new AccessDeniedException("No puedes asignar el rol " + role.getName()
                            + " porque otorga permisos que tú no tienes");
                }
            }
        }
        return new HashSet<>(roles);
    }

    private void requireAnotherActiveAdmin(User user) {
        Long others = entityManager.createQuery("""
                select count(distinct u.id) from User u join u.roles r
                where r.code = 'ADMIN' and u.active = true and u.deletedAt is null and u.id <> :id""", Long.class)
                .setParameter("id", user.getId()).getSingleResult();
        if (others == 0) {
            throw new ConflictException("Es el último administrador activo del hotel. Asigna otro administrador primero.");
        }
    }

    private static void requireNotSelf(AuthPrincipal actor, User user, String message) {
        if (actor.userId().equals(user.getId())) {
            throw new BusinessException(message);
        }
    }

    private static void requireAdmin(AuthPrincipal actor, String message) {
        if (!actor.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException(message);
        }
    }

    private User findStaff(Long id) {
        return userRepository.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .filter(u -> u.getRoles().stream().anyMatch(r -> !Role.GUEST.equals(r.getCode())))
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    private UserDetailResponse detail(User user) {
        List<UserDetailResponse.Override> overrides = overrideRepository.findByUserIdWithPermission(user.getId()).stream()
                .map(o -> new UserDetailResponse.Override(o.getPermission().getCode(), o.getEffect().name(), o.getReason()))
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .toList();
        List<String> effective = permissionResolver.principalFor(user).permissions().stream().sorted().toList();
        return new UserDetailResponse(userMapper.toResponse(user), overrides, effective);
    }

    private static Specification<User> staffOnly() {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            var subUser = sub.from(User.class);
            var role = subUser.join("roles", JoinType.INNER);
            sub.select(subUser.get("id")).where(cb.equal(subUser.get("id"), root.get("id")),
                    cb.notEqual(role.get("code"), Role.GUEST));
            return cb.exists(sub);
        };
    }

    private static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    private static boolean hasRole(User user, String code) {
        return user.getRoles().stream().anyMatch(r -> code.equals(r.getCode()));
    }

    private static Set<Long> roleIds(Set<Role> roles) {
        return roles.stream().map(Role::getId).collect(Collectors.toSet());
    }

    private static Map<String, Object> snapshot(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", user.getEmail());
        data.put("firstName", user.getFirstName());
        data.put("lastName", user.getLastName());
        data.put("phone", user.getPhone());
        data.put("active", user.isActive());
        data.put("roles", user.getRoles().stream().map(Role::getCode).sorted().toList());
        return data;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
