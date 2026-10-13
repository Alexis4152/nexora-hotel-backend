package com.nexora.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Reloj único inyectable. Las reglas dependientes de la hora (políticas de cancelación, expiración de
 * retenciones, bloqueo por intentos fallidos) usan este {@link Clock} en vez de {@code now()} directo,
 * para poder probarlas con un reloj fijo.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
