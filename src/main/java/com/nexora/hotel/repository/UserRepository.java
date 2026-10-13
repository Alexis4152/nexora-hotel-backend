package com.nexora.hotel.repository;

import com.nexora.hotel.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** Consultas filtradas automáticamente por el hotel actual (User es entidad del tenant). */
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Usuario de plataforma (SUPER_ADMIN): solo visible ejecutando en ROOT. */
    Optional<User> findByEmailAndHotelIdIsNull(String email);
}
