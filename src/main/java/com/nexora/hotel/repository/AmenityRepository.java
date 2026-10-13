package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Amenity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AmenityRepository extends JpaRepository<Amenity, Long> {

    List<Amenity> findByActiveTrueOrderByNameAsc();

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    boolean existsByNameIgnoreCase(String name);
}
