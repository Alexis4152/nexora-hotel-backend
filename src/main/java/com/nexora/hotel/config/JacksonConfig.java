package com.nexora.hotel.config;

import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La API responde con DTOs, no con entidades; este módulo es una red de seguridad para que un proxy
 * LAZY de Hibernate nunca rompa la serialización (mismo criterio que DemoPV y Citas).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Hibernate6Module hibernate6Module() {
        return new Hibernate6Module();
    }
}
