package com.nexora.hotel.repository;

import com.nexora.hotel.entity.ExtraService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExtraServiceRepository extends JpaRepository<ExtraService, Long> {

    List<ExtraService> findAllByOrderBySortOrderAscNameAsc();

    List<ExtraService> findByActiveTrueAndBookableOnlineTrueOrderBySortOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
