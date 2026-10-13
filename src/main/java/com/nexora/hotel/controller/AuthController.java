package com.nexora.hotel.controller;

import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.request.ChangePasswordRequest;
import com.nexora.hotel.dto.request.ForgotPasswordRequest;
import com.nexora.hotel.dto.request.LoginRequest;
import com.nexora.hotel.dto.request.ResetPasswordRequest;
import com.nexora.hotel.dto.response.SessionResponse;
import com.nexora.hotel.dto.response.SessionUser;
import com.nexora.hotel.service.AuthService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sesión. El refresh token viaja SOLO en una cookie httpOnly limitada a {@code /api/auth}, una por hotel
 * ({@code nx_rt_<slug>}) para que el mismo navegador pueda tener abiertas sesiones de hoteles distintos.
 * Refresh y logout exigen {@code X-Requested-With}: un formulario de otro sitio no puede mandarlo (CSRF).
 */
@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String COOKIE_PREFIX = "nx_rt_";
    private static final String PLATFORM_COOKIE = COOKIE_PREFIX + "_platform";

    private final AuthService authService;
    private final HotelDirectory hotelDirectory;
    private final AppProperties props;

    @Operation(summary = "Inicia sesión en el hotel del header X-Hotel, o en la plataforma sin header")
    @PostMapping("/login")
    public ApiResponse<SessionResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = TenantContext.callAs(loginTenant(), () -> authService.login(request));
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(result.session());
    }

    @Operation(summary = "Renueva la sesión con la cookie del refresh token (lo rota)")
    @PostMapping("/refresh")
    public ApiResponse<SessionResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        requireAjax(request);
        Long hotelId = TenantContext.hotelIdOrNull();
        String raw = readCookie(request);
        AuthService.LoginResult result = TenantContext.callAs(TenantContext.ROOT, () -> authService.refresh(raw, hotelId));
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(result.session());
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        requireAjax(request);
        authService.logout(readCookie(request));
        clearRefreshCookie(response);
        return ApiResponse.ok(null, "Sesión cerrada");
    }

    @GetMapping("/me")
    public ApiResponse<SessionUser> me() {
        return ApiResponse.ok(authService.currentSession());
    }

    @Operation(summary = "Envía un enlace de recuperación. Responde igual exista o no la cuenta")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        TenantContext.runAs(loginTenant(), () -> authService.forgotPassword(request.email()));
        return ApiResponse.ok(null, "Si el correo está registrado, te enviamos un enlace para restablecer tu contraseña.");
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        TenantContext.runAs(TenantContext.ROOT, () -> authService.resetPassword(request));
        return ApiResponse.ok(null, "Contraseña actualizada. Ya puedes iniciar sesión.");
    }

    @PostMapping("/change-password")
    public ApiResponse<SessionResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                        HttpServletResponse response) {
        AuthService.LoginResult result = authService.changePassword(request);
        setRefreshCookie(response, result.refreshToken());
        return ApiResponse.ok(result.session(), "Contraseña actualizada");
    }

    // ── Cookie ────────────────────────────────────────────────────────────────────────

    /** Hotel del enlace; sin hotel, la raíz (solo cuentas de plataforma). */
    private static long loginTenant() {
        Long hotelId = TenantContext.hotelIdOrNull();
        return hotelId != null ? hotelId : TenantContext.ROOT;
    }

    private String cookieName() {
        Long hotelId = TenantContext.hotelIdOrNull();
        if (hotelId == null) {
            return PLATFORM_COOKIE;
        }
        return COOKIE_PREFIX + TenantContext.callAs(TenantContext.ROOT, () -> hotelDirectory.get(hotelId)).getSlug();
    }

    private void setRefreshCookie(HttpServletResponse response, String raw) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(raw, props.jwt().refreshTtl().toSeconds()).toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(cookieName(), value)
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite(props.cookie().sameSite())
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        String name = cookieName();
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void requireAjax(HttpServletRequest request) {
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            throw new AccessDeniedException("Solicitud no permitida");
        }
    }
}
