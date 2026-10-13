package com.nexora.hotel.repository;

import com.nexora.hotel.entity.CashSession;
import com.nexora.hotel.enums.PaymentEnums.CashSessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CashSessionRepository extends JpaRepository<CashSession, Long> {

    Optional<CashSession> findByUserIdAndStatus(Long userId, CashSessionStatus status);

    List<CashSession> findByStatusOrderByOpenedAtAsc(CashSessionStatus status);

    Page<CashSession> findAllByOrderByOpenedAtDesc(Pageable pageable);

    Page<CashSession> findByUserIdOrderByOpenedAtDesc(Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CashSession s where s.id = :id")
    Optional<CashSession> findByIdForUpdate(Long id);
}
