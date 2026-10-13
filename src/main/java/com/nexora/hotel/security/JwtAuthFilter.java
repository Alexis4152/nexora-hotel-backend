package com.nexora.hotel.security;

import com.nexora.hotel.i18n.Translator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Autentica cada request con el JWT de {@code Authorization: Bearer}. El usuario se vuelve a leer de la BD
 * en cada request (en ROOT, todavía no se sabe su hotel): una cuenta desactivada o un hotel dado de baja
 * pierden acceso de inmediato, sin esperar a que expire el token.
 * <p>
 * Autoridades: cada permiso efectivo ({@code RESERVATIONS:CREATE}), {@code ROLE_<rol>} y
 * {@code HOTEL_STAFF} para personal de hotel (todo rol distinto de GUEST), que es lo que exige
 * {@code /api/admin/**}. Así un SUPER_ADMIN, que opera en ROOT, nunca entra a endpoints de hotel.
 * <p>
 * Token ausente o inválido: la request sigue como anónima y las reglas de {@code SecurityConfig} deciden.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String HOTEL_STAFF = "HOTEL_STAFF";

    /** Con contraseña temporal solo se permite esto hasta cambiarla. */
    private static final Set<String> ALLOWED_WHILE_MUST_CHANGE = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout", "/api/auth/refresh");

    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final HotelDirectory hotelDirectory;
    private final PermissionResolver permissionResolver;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenProvider tokenProvider, UserRepository userRepository, HotelDirectory hotelDirectory,
                         PermissionResolver permissionResolver, ObjectMapper objectMapper) {
        this.tokenProvider = tokenProvider;
        this.userRepository = userRepository;
        this.hotelDirectory = hotelDirectory;
        this.permissionResolver = permissionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = bearer(request);
        if (token != null) {
            User user = tokenProvider.parseUserId(token)
                    .flatMap(id -> TenantContext.callAs(TenantContext.ROOT, () -> userRepository.findById(id)))
                    .filter(this::canOperate)
                    .orElse(null);
            if (user != null) {
                if (user.isMustChangePassword() && !ALLOWED_WHILE_MUST_CHANGE.contains(request.getRequestURI())) {
                    writeMustChangePassword(response);
                    return;
                }
                AuthPrincipal principal = TenantContext.callAs(TenantContext.ROOT, () -> permissionResolver.principalFor(user));
                var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities(principal));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private boolean canOperate(User user) {
        if (!user.isActive() || user.getDeletedAt() != null) {
            return false;
        }
        return user.getHotelId() == null
                || TenantContext.callAs(TenantContext.ROOT, () -> hotelDirectory.isActive(user.getHotelId()));
    }

    private static List<GrantedAuthority> authorities(AuthPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        boolean staff = principal.hotelId() != null && principal.roles().stream().anyMatch(r -> !Role.GUEST.equals(r));
        if (staff) {
            authorities.add(new SimpleGrantedAuthority(HOTEL_STAFF));
        }
        return authorities;
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private void writeMustChangePassword(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(Translator.tr("Debes cambiar tu contraseña temporal antes de continuar")));
    }
}
