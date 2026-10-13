package com.nexora.hotel.repository;

import com.nexora.hotel.entity.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoomTypeRepository extends JpaRepository<RoomType, Long> {

    List<RoomType> findAllByOrderBySortOrderAscNameAsc();

    List<RoomType> findByActiveTrueOrderBySortOrderAscNameAsc();

    boolean existsByCode(String code);
}
