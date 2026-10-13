package com.nexora.hotel.security;

import com.nexora.hotel.i18n.Translator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Límite de peticiones por IP en endpoints susceptibles de abuso. Complementa al bloqueo por cuenta del
 * login: ese frena a quien prueba muchas contraseñas en UNA cuenta; éste, a quien prueba muchas cuentas
 * desde la misma IP. Responde 429 con {@code Retry-After} y un mensaje sin detalles internos.
 * <p>
 * Los endpoints de reservación, disponibilidad y pagos se agregan aquí en sus fases (6 y 7).
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private record Rule(String id, String method, String pattern, int capacity, Duration window) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("login", "POST", "/api/auth/login", 10, Duration.ofMinutes(1)),
            new Rule("forgot", "POST", "/api/auth/forgot-password", 5, Duration.ofMinutes(15)),
            new Rule("reset", "POST", "/api/auth/reset-password", 10, Duration.ofMinutes(15)),
            new Rule("refresh", "POST", "/api/auth/refresh", 60, Duration.ofMinutes(1)),
            // Crear reservaciones y pedir enlaces envía correos y aparta habitaciones: límites bajos.
            new Rule("booking", "POST", "/api/public/reservations", 5, Duration.ofMinutes(10)),
            new Rule("register", "POST", "/api/public/account/register", 5, Duration.ofMinutes(15)),
            new Rule("verify", "POST", "/api/public/account/verify", 20, Duration.ofMinutes(15)),
            new Rule("resend", "POST", "/api/public/account/resend", 5, Duration.ofMinutes(15)),
            new Rule("lookup", "POST", "/api/public/reservations/lookup", 5, Duration.ofMinutes(15)),
            new Rule("guest-cancel", "POST", "/api/public/reservations/*/cancel", 10, Duration.ofMinutes(15)),
            new Rule("availability", "GET", "/api/public/availability", 60, Duration.ofMinutes(1)),
            new Rule("guest-pay", "POST", "/api/public/reservations/*/payments", 10, Duration.ofMinutes(15)),
            new Rule("payment-status", "GET", "/api/public/payments/*", 30, Duration.ofMinutes(1)),
            new Rule("webhook", "POST", "/api/public/webhooks/**", 600, Duration.ofMinutes(1)),
            new Rule("guest-view", "GET", "/api/public/reservations/*", 30, Duration.ofMinutes(1)),
            new Rule("report-export", "GET", "/api/admin/reports/*/export", 30, Duration.ofMinutes(1)),
            new Rule("public", "GET", "/api/public/**", 300, Duration.ofMinutes(1)));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final RateLimiterService rateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        for (Rule rule : RULES) {
            if (rule.method().equals(request.getMethod()) && matcher.match(rule.pattern(), path)) {
                long waitSeconds = rateLimiter.tryConsume(rule.id() + "|" + request.getRemoteAddr(),
                        rule.capacity(), rule.window());
                if (waitSeconds > 0) {
                    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(waitSeconds));
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    objectMapper.writeValue(response.getWriter(),
                            ApiResponse.error(Translator.tr("Demasiadas solicitudes. Intenta de nuevo en unos minutos.")));
                    return false;
                }
                return true;
            }
        }
        return true;
    }
}
