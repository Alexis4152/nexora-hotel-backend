package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HotelRepository extends JpaRepository<Hotel, Long> {

    Optional<Hotel> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Hotel> findByActiveTrueOrderByIdAsc();
}
