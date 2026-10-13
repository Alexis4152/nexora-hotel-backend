package com.nexora.hotel.tenant;

import com.nexora.hotel.i18n.Translator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.security.AuthPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Fija el hotel de la request ({@link TenantContext}). Corre dentro de la cadena de Spring Security,
 * después de la autenticación (ver {@code SecurityConfig}):
 * <ul>
 *   <li>Con sesión: el hotel del usuario; el SUPER_ADMIN trabaja en ROOT. <b>Manda sobre el header</b>:
 *       un usuario nunca opera en un hotel que no es el suyo, aunque mande otro {@code X-Hotel}.</li>
 *   <li>Sin sesión: el hotel ACTIVO del header {@code X-Hotel} (slug del enlace {@code /h/<slug>}).
 *       Un slug inexistente, inactivo o mal formado responde 404.</li>
 *   <li>Sin ninguno: NONE, no ve datos de ningún hotel.</li>
 * </ul>
 */
public class TenantFilter extends OncePerRequestFilter {

    public static final String HOTEL_HEADER = "X-Hotel";
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$");

    private final HotelDirectory hotelDirectory;
    private final ObjectMapper objectMapper;

    public TenantFilter(HotelDirectory hotelDirectory, ObjectMapper objectMapper) {
        this.hotelDirectory = hotelDirectory;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            AuthPrincipal principal = currentPrincipal();
            if (principal != null) {
                TenantContext.set(principal.hotelId() == null ? TenantContext.ROOT : principal.hotelId());
            } else {
                String header = request.getHeader(HOTEL_HEADER);
                if (header != null && !header.isBlank()) {
                    String slug = header.trim().toLowerCase();
                    Long hotelId = SLUG.matcher(slug).matches() ? TenantContext.callAs(TenantContext.ROOT,
                            () -> hotelDirectory.activeIdBySlug(slug)) : null;
                    if (hotelId == null) {
                        writeHotelNotFound(response);
                        return;
                    }
                    TenantContext.set(hotelId);
                } else {
                    TenantContext.set(TenantContext.NONE);
                }
            }
            MDC.put("hotel", String.valueOf(TenantContext.get()));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("hotel");
        }
    }

    private AuthPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthPrincipal p ? p : null;
    }

    private void writeHotelNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(Translator.tr("Hotel no encontrado o inactivo")));
    }
}
