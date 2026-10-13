package com.nexora.hotel.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detiene el arranque en producción si la configuración es insegura. Prefiere no arrancar a operar
 * con un secreto de ejemplo, una cookie sin {@code Secure} o imágenes guardadas dentro del contenedor
 * (que se perderían en el siguiente redeploy).
 */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionReadinessValidator {

    // Frases típicas de valores de ejemplo. Son largas a propósito: un secreto aleatorio puede contener
    // "dev" o "test" por azar y no debe impedir el arranque.
    private static final List<String> WEAK_MARKERS = List.of("changeme", "change-me", "change_me", "example",
            "ejemplo", "desarrollo", "development", "dev-secret", "test-secret", "nunca-usar", "placeholder");

    private final AppProperties props;

    @PostConstruct
    void validate() {
        List<String> problems = new ArrayList<>();

        if (looksWeak(props.jwt().secret())) {
            problems.add("APP_JWT_SECRET parece un valor de ejemplo; genera uno aleatorio (openssl rand -base64 48)");
        }
        if (looksWeak(props.encryption().key())) {
            problems.add("APP_ENCRYPTION_KEY parece un valor de ejemplo; genera uno aleatorio (openssl rand -base64 48)");
        }
        if (props.jwt().secret().equals(props.encryption().key())) {
            problems.add("APP_JWT_SECRET y APP_ENCRYPTION_KEY deben ser distintos");
        }
        if (!props.cookie().secure()) {
            problems.add("APP_COOKIE_SECURE debe ser true en producción (requiere HTTPS)");
        }
        if (!Path.of(props.storage().dir()).isAbsolute()) {
            problems.add("APP_STORAGE_DIR debe ser una ruta absoluta montada como volumen persistente");
        }
        for (String origin : props.cors().allowedOrigins()) {
            String o = origin.trim().toLowerCase(Locale.ROOT);
            if (!o.startsWith("https://") || o.contains("localhost") || o.contains("127.0.0.1")) {
                problems.add("APP_CORS_ALLOWED_ORIGINS solo debe contener orígenes https públicos: " + origin);
            }
        }
        if (!props.frontendUrl().startsWith("https://")) {
            problems.add("APP_FRONTEND_URL debe usar https");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuración insegura para producción:\n - " + String.join("\n - ", problems));
        }
    }

    private static boolean looksWeak(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return WEAK_MARKERS.stream().anyMatch(v::contains);
    }
}
