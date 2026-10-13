package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Consultas filtradas automáticamente por el hotel actual (Role es entidad del tenant). */
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    List<Role> findByActiveTrueOrderByNameAsc();
}
