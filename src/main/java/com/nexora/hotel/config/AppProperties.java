package com.nexora.hotel.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Parámetros técnicos de despliegue ({@code app.*}), validados al arrancar: si falta un secreto la
 * aplicación no inicia. Son SOLO de infraestructura; la configuración funcional del hotel vive en
 * PostgreSQL y nunca se expone por endpoints públicos.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotBlank String frontendUrl,
        @NotBlank String publicUrl,
        @Valid @NotNull Cors cors,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Cookie cookie,
        @Valid @NotNull Encryption encryption,
        @Valid @NotNull Storage storage,
        @Valid @NotNull @DefaultValue Security security,
        @Valid @NotNull @DefaultValue Bootstrap bootstrap,
        @Valid @NotNull @DefaultValue Mail mail,
        @Valid @NotNull @DefaultValue Payments payments) {

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }

    public record Jwt(
            @NotBlank(message = "Define APP_JWT_SECRET (mínimo 32 caracteres)")
            @Size(min = 32, message = "APP_JWT_SECRET debe tener al menos 32 caracteres") String secret,
            @NotNull Duration accessTtl,
            @NotNull Duration refreshTtl) {
    }

    public record Cookie(boolean secure, @NotBlank String sameSite) {
    }

    public record Encryption(
            @NotBlank(message = "Define APP_ENCRYPTION_KEY (mínimo 32 caracteres)")
            @Size(min = 32, message = "APP_ENCRYPTION_KEY debe tener al menos 32 caracteres") String key) {
    }

    public record Storage(@NotBlank String dir, @Min(1024) long maxImageBytes) {
    }

    /**
     * Protecciones de autenticación. Tienen pisos mínimos: no se pueden relajar por debajo de lo seguro.
     *
     * @param rateLimitEnabled  apagar solo en pruebas automatizadas
     * @param maxFailedLogins   intentos fallidos antes de bloquear la cuenta (3–10)
     * @param lockDuration      tiempo de bloqueo tras exceder los intentos
     * @param resetTokenTtl     vigencia del enlace de recuperación de contraseña
     */
    public record Security(
            @DefaultValue("true") boolean rateLimitEnabled,
            @DefaultValue("5") @Min(3) @jakarta.validation.constraints.Max(10) int maxFailedLogins,
            @DefaultValue("PT15M") Duration lockDuration,
            @DefaultValue("PT30M") Duration resetTokenTtl) {
    }

    /**
     * Alta inicial. Si no existe ningún SUPER_ADMIN y se definen correo y contraseña, se crea al
     * arrancar. {@code demoData} solo tiene efecto en el perfil dev.
     */
    public record Bootstrap(
            String superAdminEmail,
            String superAdminPassword,
            @DefaultValue("false") boolean demoData,
            String demoPassword) {
    }

    /**
     * Endpoints de la pasarela. Cada hotel elige en su configuración si opera en pruebas (sandbox) o en producción.
     */
    public record Payments(
            @DefaultValue("https://sandbox-api.openpay.mx") @NotBlank String openpaySandboxUrl,
            @DefaultValue("https://api.openpay.mx") @NotBlank String openpayProductionUrl,
            @DefaultValue("PT20S") Duration timeout) {
    }

    /** SMTP de la plataforma (recuperación de contraseña). Sin host no se envían correos. */
    public record Mail(String host, @DefaultValue("587") int port, String username, String password,
                       @DefaultValue("no-reply@nexora.mx") String from) {

        public boolean configured() {
            return host != null && !host.isBlank();
        }
    }
}
