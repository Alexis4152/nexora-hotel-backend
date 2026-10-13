package com.nexora.hotel.repository;

import com.nexora.hotel.entity.HotelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HotelPolicyRepository extends JpaRepository<HotelPolicy, Long> {

    /** Versión vigente: la más alta. */
    Optional<HotelPolicy> findFirstByOrderByVersionDesc();
}
