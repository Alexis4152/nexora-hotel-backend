package com.nexora.hotel.repository;

import com.nexora.hotel.entity.CashMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashMovementRepository extends JpaRepository<CashMovement, Long> {

    List<CashMovement> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);
}
