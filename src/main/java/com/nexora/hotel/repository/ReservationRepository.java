package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    Optional<Reservation> findByFolio(String folio);

    boolean existsByFolio(String folio);

    /** Bloquea la reservación: dos cambios de estado simultáneos (p. ej. confirmar dos veces) se serializan. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    List<Reservation> findByStatusInAndExpiresAtBefore(List<ReservationStatus> statuses, Instant before);

    List<Reservation> findByCustomerIdOrderByCheckInDesc(Long customerId);
}
