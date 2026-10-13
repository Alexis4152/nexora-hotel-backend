package com.nexora.hotel.config;

import com.nexora.hotel.security.AuthPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Llena {@code created_by}/{@code updated_by} con el usuario autenticado (nunca con un valor del cliente).
 * Las fechas de creación/modificación las pone Hibernate ({@code @CreationTimestamp}/{@code @UpdateTimestamp}).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "currentUserAuditor")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> currentUserAuditor() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
                return Optional.of(principal.userId());
            }
            return Optional.empty();
        };
    }
}
