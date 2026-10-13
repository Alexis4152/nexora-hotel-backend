package com.nexora.hotel.repository;

import com.nexora.hotel.entity.HotelSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Una fila por hotel; el filtro de tenant la deja como única visible. */
public interface HotelSettingsRepository extends JpaRepository<HotelSettings, Long> {

    Optional<HotelSettings> findFirstBy();
}
