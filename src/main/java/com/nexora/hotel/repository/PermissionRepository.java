package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findAllByOrderBySortOrderAsc();

    List<Permission> findByCodeIn(Collection<String> codes);

    Optional<Permission> findByCode(String code);

    long countByReservedTrue();
}
