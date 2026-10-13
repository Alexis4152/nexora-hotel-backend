package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Room;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @EntityGraph(attributePaths = "roomType")
    List<Room> findAllByOrderByFloorAscSortOrderAscNumberAsc();

    /** Bloqueo pesimista de la fila: serializa operaciones que ocupan fechas de la misma habitación. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    boolean existsByNumberIgnoreCase(String number);

    long countByRoomTypeIdAndActiveTrue(Long roomTypeId);

    long countByRoomTypeId(Long roomTypeId);
}
