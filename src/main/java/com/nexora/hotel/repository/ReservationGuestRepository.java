package com.nexora.hotel.repository;

import com.nexora.hotel.entity.ReservationGuest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReservationGuestRepository extends JpaRepository<ReservationGuest, Long> {

    List<ReservationGuest> findByReservationIdOrderByIdAsc(Long reservationId);

    void deleteByReservationId(Long reservationId);
}
