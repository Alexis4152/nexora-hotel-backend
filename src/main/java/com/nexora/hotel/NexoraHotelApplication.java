package com.nexora.hotel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API de Nexora Hotel. Multi-hotel: cada dato de negocio pertenece a un hotel ({@code hotel_id})
 * y Hibernate lo filtra automáticamente (ver paquete {@code tenant}).
 * <p>
 * Se excluye {@link UserDetailsServiceAutoConfiguration}: la autenticación es propia (JWT) y sin
 * esto Spring Boot crearía un usuario en memoria con contraseña impresa en el log.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class NexoraHotelApplication {

    /**
     * La JVM siempre en UTC. Con {@code hibernate.jdbc.time_zone=UTC}, si la JVM corre en otra zona Hibernate corre
     * las columnas TIME (hora de check-in, check-out, recepción) por el desfase local: 15:00 se leía como 09:00 en
     * UTC-6. Las fechas de negocio usan siempre la zona de cada hotel de forma explícita, nunca la de la JVM.
     */
    static {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(NexoraHotelApplication.class, args);
    }
}
