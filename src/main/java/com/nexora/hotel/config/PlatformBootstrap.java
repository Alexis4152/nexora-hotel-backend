package com.nexora.hotel.config;

import com.nexora.hotel.service.PlatformAccountService;
import com.nexora.hotel.validation.ValidationPatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Crea el SUPER_ADMIN de Nexora al arrancar si todavía no existe ninguno y se definieron
 * {@code APP_BOOTSTRAP_SUPER_ADMIN_EMAIL}/{@code _PASSWORD}. Nunca modifica uno existente: después del
 * primer arranque esas variables se pueden (y conviene) borrar.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PlatformBootstrap implements ApplicationRunner {

    private final AppProperties props;
    private final PlatformAccountService platformAccounts;

    @Override
    public void run(ApplicationArguments args) {
        if (platformAccounts.superAdminExists()) {
            return;
        }
        String email = props.bootstrap().superAdminEmail();
        String password = props.bootstrap().superAdminPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.warn("No hay SUPER_ADMIN. Define APP_BOOTSTRAP_SUPER_ADMIN_EMAIL y APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD para crearlo.");
            return;
        }
        if (password.length() < 12 || !password.matches(ValidationPatterns.PASSWORD)) {
            throw new IllegalStateException(
                    "APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD debe tener 12+ caracteres con mayúscula, minúscula y número");
        }
        platformAccounts.createSuperAdmin(email, "Nexora", "Plataforma", password);
        log.info("SUPER_ADMIN creado: {}", email);
    }
}
