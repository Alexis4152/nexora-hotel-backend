package com.nexora.hotel.service.impl;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.request.CreateHotelRequest;
import com.nexora.hotel.dto.request.UpdateHotelRequest;
import com.nexora.hotel.dto.response.HotelSummary;
import com.nexora.hotel.dto.response.ProvisionedHotelResponse;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.Permission;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.HotelRepository;
import com.nexora.hotel.repository.PermissionRepository;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.service.HotelProvisioningService;
import com.nexora.hotel.service.RefreshTokenService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alta de hoteles. Cada paso corre en su propia transacción con el tenant correcto: el hotel en ROOT y sus
 * roles/admin DENTRO del hotel (Hibernate fija el tenant al abrir la sesión, por eso no puede ser una sola
 * transacción). Si la siembra falla, el hotel recién creado se elimina para no dejar uno sin administrador.
 */
@Service
public class HotelProvisioningServiceImpl implements HotelProvisioningService {

    private final HotelRepository hotelRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final HotelDirectory hotelDirectory;
    private final PermissionResolver permissionResolver;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public HotelProvisioningServiceImpl(HotelRepository hotelRepository, RoleRepository roleRepository,
                                        UserRepository userRepository, PermissionRepository permissionRepository,
                                        PasswordEncoder passwordEncoder, HotelDirectory hotelDirectory,
                                        PermissionResolver permissionResolver, RefreshTokenService refreshTokenService,
                                        AuditService auditService, AppProperties props, JdbcTemplate jdbc,
                                        PlatformTransactionManager txManager) {
        this.hotelRepository = hotelRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.hotelDirectory = hotelDirectory;
        this.permissionResolver = permissionResolver;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.props = props;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public List<HotelSummary> list() {
        Map<Long, Long> staff = staffCounts();
        return TenantContext.callAs(TenantContext.ROOT, () -> hotelRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(h -> summary(h, staff.getOrDefault(h.getId(), 0L)))
                .toList());
    }

    @Override
    public ProvisionedHotelResponse create(CreateHotelRequest request) {
        String temporary = SecureTokens.temporaryPassword();
        return provision(request, temporary, true);
    }

    @Override
    public ProvisionedHotelResponse createWithPassword(CreateHotelRequest request, String adminPassword) {
        return provision(request, adminPassword, false);
    }

    private ProvisionedHotelResponse provision(CreateHotelRequest request, String password, boolean mustChange) {
        String slug = request.slug().trim().toLowerCase(Locale.ROOT);
        validateZone(request.timezone());
        if (Boolean.TRUE.equals(TenantContext.callAs(TenantContext.ROOT, () -> hotelRepository.existsBySlug(slug)))) {
            throw new ConflictException("Ya existe un hotel con el enlace /h/" + slug);
        }

        Hotel hotel = TenantContext.callAs(TenantContext.ROOT, () -> tx.execute(s -> hotelRepository.save(Hotel.builder()
                .slug(slug).name(request.name().trim()).timezone(request.timezone()).build())));

        User admin;
        try {
            admin = TenantContext.callAs(hotel.getId(), () -> tx.execute(s -> {
                Role adminRole = seedRoles();
                seedConfiguration(hotel.getId());
                return userRepository.save(User.builder()
                        .email(request.adminEmail())
                        .firstName(request.adminFirstName().trim())
                        .lastName(request.adminLastName().trim())
                        .passwordHash(passwordEncoder.encode(password))
                        .mustChangePassword(mustChange)
                        .roles(new HashSet<>(Set.of(adminRole)))
                        .build());
            }));
        } catch (RuntimeException e) {
            TenantContext.runAs(TenantContext.ROOT, () -> tx.executeWithoutResult(s -> hotelRepository.deleteById(hotel.getId())));
            throw e;
        }

        hotelDirectory.evict();
        TenantContext.runAs(TenantContext.ROOT, () -> tx.executeWithoutResult(s -> auditService.record(
                AuditEntry.success("HOTEL_CREATED", "Hotel", hotel.getId()).hotelId(hotel.getId())
                        .after(Map.of("slug", hotel.getSlug(), "name", hotel.getName(), "adminEmail", admin.getEmail()))
                        .build())));
        return new ProvisionedHotelResponse(summary(hotel, 1), admin.getEmail(), mustChange ? password : null);
    }

    /** Lo único que necesita quien limpia: ver el tablero y marcar habitaciones listas o por limpiar. */
    private static final Set<String> HOUSEKEEPER_PERMISSIONS = Set.of("HOUSEKEEPING:VIEW", "HOUSEKEEPING:UPDATE");

    /**
     * Roles de sistema del hotel. ADMIN recibe todo el catálogo; RECEPTIONIST, el default documentado; HOUSEKEEPER,
     * solo limpieza (ve fechas de salida y llegada, nunca nombres de huéspedes ni dinero).
     */
    private Role seedRoles() {
        List<Permission> catalog = permissionRepository.findAllByOrderBySortOrderAsc();
        Role admin = roleRepository.save(Role.builder().code(Role.ADMIN).name("Administrador")
                .description("Acceso completo al hotel").system(true).permissions(new HashSet<>(catalog)).build());
        roleRepository.save(Role.builder().code(Role.RECEPTIONIST).name("Recepcionista")
                .description("Operación diaria de recepción").system(true)
                .permissions(catalog.stream().filter(Permission::isDefaultReceptionist).collect(Collectors.toSet()))
                .build());
        roleRepository.save(Role.builder().code(Role.HOUSEKEEPER).name("Camarista")
                .description("Limpieza de habitaciones").system(true)
                .permissions(catalog.stream().filter(p -> HOUSEKEEPER_PERMISSIONS.contains(p.getCode())).collect(Collectors.toSet()))
                .build());
        roleRepository.save(Role.builder().code(Role.GUEST).name("Huésped")
                .description("Huésped con cuenta en el portal").system(true).build());
        return admin;
    }

    /**
     * Configuración inicial: horarios por defecto, identidad vacía, políticas v1 e impuestos editables
     * (IVA 16 % e ISH 3 %; el ISH varía por estado y el administrador debe ajustarlo) y cobro en recepción activo
     * (efectivo, tarjeta y transferencia; el pago en línea se activa al capturar las credenciales de la pasarela).
     */
    private void seedConfiguration(Long hotelId) {
        jdbc.update("INSERT INTO hotel_settings (hotel_id) VALUES (?)", hotelId);
        jdbc.update("INSERT INTO hotel_branding (hotel_id) VALUES (?)", hotelId);
        jdbc.update("INSERT INTO hotel_policies (hotel_id, version) VALUES (?, 1)", hotelId);
        jdbc.update("INSERT INTO payment_settings (hotel_id) VALUES (?)", hotelId);
        jdbc.update("""
                INSERT INTO tax_rates (hotel_id, name, percent, applies_to, included_in_price)
                VALUES (?, 'IVA', 16.00, 'ALL', FALSE), (?, 'ISH (hospedaje)', 3.00, 'ROOM', FALSE)""", hotelId, hotelId);
    }

    @Override
    public HotelSummary update(Long hotelId, UpdateHotelRequest request) {
        validateZone(request.timezone());
        Hotel saved = TenantContext.callAs(TenantContext.ROOT, () -> tx.execute(s -> {
            Hotel hotel = hotelRepository.findById(hotelId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hotel no encontrado"));
            Map<String, Object> before = Map.of("name", hotel.getName(), "timezone", hotel.getTimezone(), "active", hotel.isActive());
            hotel.setName(request.name().trim());
            hotel.setTimezone(request.timezone());
            hotel.setActive(request.active());
            auditService.record(AuditEntry.success(request.active() ? "HOTEL_UPDATED" : "HOTEL_DEACTIVATED", "Hotel", hotelId)
                    .hotelId(hotelId).before(before)
                    .after(Map.of("name", hotel.getName(), "timezone", hotel.getTimezone(), "active", hotel.isActive()))
                    .build());
            return hotel;
        }));
        hotelDirectory.evict();
        return summary(saved, staffCounts().getOrDefault(hotelId, 0L));
    }

    @Override
    public TemporaryPasswordResponse resetAdminPassword(Long hotelId) {
        TenantContext.callAs(TenantContext.ROOT, () -> hotelRepository.findById(hotelId))
                .orElseThrow(() -> new ResourceNotFoundException("Hotel no encontrado"));
        Long adminId = jdbc.query("""
                SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE u.hotel_id = ? AND r.code = 'ADMIN' AND u.deleted_at IS NULL
                ORDER BY u.active DESC, u.id ASC LIMIT 1""", rs -> rs.next() ? rs.getLong(1) : null, hotelId);
        if (adminId == null) {
            throw new BusinessException("El hotel no tiene administradores");
        }
        String temporary = SecureTokens.temporaryPassword();
        User admin = TenantContext.callAs(hotelId, () -> tx.execute(s -> {
            User user = userRepository.findById(adminId).orElseThrow();
            user.setPasswordHash(passwordEncoder.encode(temporary));
            user.setMustChangePassword(true);
            user.setActive(true);
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            refreshTokenService.revokeAllForUser(user.getId());
            permissionResolver.bumpUser(user.getId());
            return user;
        }));
        TenantContext.runAs(TenantContext.ROOT, () -> tx.executeWithoutResult(s -> auditService.record(
                AuditEntry.success("HOTEL_ADMIN_PASSWORD_RESET", "User", admin.getId()).hotelId(hotelId)
                        .detail("Contraseña temporal generada por la plataforma").build())));
        return new TemporaryPasswordResponse(admin.getEmail(), temporary);
    }

    private Map<Long, Long> staffCounts() {
        return jdbc.query("""
                SELECT u.hotel_id, count(DISTINCT u.id) FROM users u
                JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE u.hotel_id IS NOT NULL AND u.active AND r.code <> 'GUEST' GROUP BY u.hotel_id""",
                rs -> {
                    Map<Long, Long> map = new java.util.HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getLong(1), rs.getLong(2));
                    }
                    return map;
                });
    }

    private HotelSummary summary(Hotel hotel, long staff) {
        String url = props.frontendUrl().replaceAll("/+$", "") + "/h/" + hotel.getSlug();
        return new HotelSummary(hotel.getId(), hotel.getSlug(), hotel.getName(), hotel.getTimezone(),
                hotel.getCurrency(), hotel.isActive(), hotel.getCreatedAt(), staff, url);
    }

    private static void validateZone(String zone) {
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BusinessException("La zona horaria no es válida");
        }
    }
}
