package com.nexora.hotel.repository;

import com.nexora.hotel.entity.MaintenanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaintenanceRecordRepository extends JpaRepository<MaintenanceRecord, Long> {

    List<MaintenanceRecord> findByRoomIdOrderByStartsOnDesc(Long roomId);
}
