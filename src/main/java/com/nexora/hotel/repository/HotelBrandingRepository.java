package com.nexora.hotel.repository;

import com.nexora.hotel.entity.HotelBranding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HotelBrandingRepository extends JpaRepository<HotelBranding, Long> {

    Optional<HotelBranding> findFirstBy();
}
