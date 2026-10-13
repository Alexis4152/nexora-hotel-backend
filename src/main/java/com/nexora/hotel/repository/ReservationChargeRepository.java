package com.nexora.hotel.repository;

import com.nexora.hotel.entity.ReservationCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationChargeRepository extends JpaRepository<ReservationCharge, Long> {

    List<ReservationCharge> findByReservationIdOrderByCreatedAtAscIdAsc(Long reservationId);
}
