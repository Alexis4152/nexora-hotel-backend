package com.nexora.hotel.tenant;

import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Corre una tarea por cada hotel activo, cada una con ese hotel en {@link TenantContext} (y por tanto
 * en su propia sesión de Hibernate filtrada). Un error en un hotel se registra y no frena a los demás.
 * Base de las tareas programadas (expiración de retenciones, correos, recordatorios).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantRunner {

    private final HotelRepository hotelRepository;

    public void forEachActiveHotel(String jobName, Consumer<Hotel> job) {
        List<Hotel> hotels = TenantContext.callAs(TenantContext.ROOT, hotelRepository::findByActiveTrueOrderByIdAsc);
        for (Hotel hotel : hotels) {
            try {
                TenantContext.runAs(hotel.getId(), () -> job.accept(hotel));
            } catch (RuntimeException e) {
                log.error("Falló la tarea '{}' del hotel {} ({})", jobName, hotel.getId(), hotel.getSlug(), e);
            }
        }
    }
}
