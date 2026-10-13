package com.nexora.hotel.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Ocupaciones de habitaciones ({@code room_occupancies}). Por JDBC porque {@code daterange} no tiene tipo JPA.
 * La restricción {@code EXCLUDE} de la tabla es la que garantiza que nunca existan dos ocupaciones traslapadas
 * de la misma habitación; los servicios traducen su violación (SQLState 23P01) a un mensaje de negocio.
 * Todo rango es [entrada, salida): la fecha de salida no es una noche ocupada.
 */
@Repository
@RequiredArgsConstructor
public class OccupancyRepository {

    private final JdbcTemplate jdbc;

    public void insertBlock(long hotelId, long roomId, LocalDate startsOn, LocalDate endsOn, long maintenanceId) {
        jdbc.update("""
                INSERT INTO room_occupancies (hotel_id, room_id, stay, kind, maintenance_id)
                VALUES (?, ?, daterange(?, ?, '[)'), 'BLOCK', ?)""", hotelId, roomId, startsOn, endsOn, maintenanceId);
    }

    public int deleteBlock(long maintenanceId) {
        return jdbc.update("DELETE FROM room_occupancies WHERE kind = 'BLOCK' AND maintenance_id = ?", maintenanceId);
    }

    public void insertReservation(long hotelId, long roomId, LocalDate checkIn, LocalDate checkOut, long reservationId) {
        jdbc.update("""
                INSERT INTO room_occupancies (hotel_id, room_id, stay, kind, reservation_id)
                VALUES (?, ?, daterange(?, ?, '[)'), 'RESERVATION', ?)""", hotelId, roomId, checkIn, checkOut, reservationId);
    }

    public int deleteForReservation(long reservationId) {
        return jdbc.update("DELETE FROM room_occupancies WHERE reservation_id = ?", reservationId);
    }

    /** Salida anticipada: la ocupación termina en {@code newEnd} y las noches siguientes quedan libres. */
    public int shrinkReservation(long reservationId, LocalDate newEnd) {
        return jdbc.update("""
                UPDATE room_occupancies SET stay = daterange(lower(stay), ?, '[)')
                WHERE reservation_id = ? AND lower(stay) < ? AND upper(stay) > ?""", newEnd, reservationId, newEnd, newEnd);
    }

    /** Reservaciones o retenciones vigentes o futuras (que terminan después de {@code today}). */
    public long futureCommitments(long roomId, LocalDate today) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM room_occupancies
                WHERE room_id = ? AND kind IN ('RESERVATION','HOLD') AND upper(stay) > ?""", Long.class, roomId, today);
        return count == null ? 0 : count;
    }

    /**
     * Habitaciones del tipo libres en todo [entrada, salida): activas, no fuera de servicio y sin ninguna
     * ocupación traslapada (las retenciones vencidas no cuentan). {@code excludeReservationId} permite que una
     * reservación que se está modificando no choque consigo misma.
     */
    public List<Long> availableRoomIds(long hotelId, long roomTypeId, LocalDate checkIn, LocalDate checkOut,
                                       Long excludeReservationId) {
        return jdbc.queryForList("""
                SELECT r.id FROM rooms r
                WHERE r.hotel_id = ? AND r.room_type_id = ? AND r.active AND r.operational_status <> 'OUT_OF_SERVICE'
                  AND NOT EXISTS (
                      SELECT 1 FROM room_occupancies o
                      WHERE o.room_id = r.id AND o.stay && daterange(?, ?, '[)')
                        AND (o.kind <> 'HOLD' OR o.expires_at > now())
                        AND (o.reservation_id IS NULL OR o.reservation_id <> ?))
                ORDER BY r.sort_order, r.number""", Long.class, hotelId, roomTypeId, checkIn, checkOut,
                excludeReservationId == null ? -1L : excludeReservationId);
    }

    /**
     * ¿La habitación está libre en [entrada, salida)? Se llama con la fila de la habitación ya bloqueada
     * (FOR UPDATE): como todo el que ocupa fechas bloquea primero la habitación, la respuesta es confiable.
     */
    public boolean isFree(long roomId, LocalDate checkIn, LocalDate checkOut, Long excludeReservationId) {
        Boolean taken = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM room_occupancies o
                    WHERE o.room_id = ? AND o.stay && daterange(?, ?, '[)')
                      AND (o.kind <> 'HOLD' OR o.expires_at > now())
                      AND (o.reservation_id IS NULL OR o.reservation_id <> ?))""", Boolean.class,
                roomId, checkIn, checkOut, excludeReservationId == null ? -1L : excludeReservationId);
        return !Boolean.TRUE.equals(taken);
    }

    /** Disponibles por tipo para todo el hotel en un rango (una sola consulta para el buscador). */
    public Map<Long, Long> availableCountByType(long hotelId, LocalDate checkIn, LocalDate checkOut) {
        Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT r.room_type_id, count(*) FROM rooms r
                WHERE r.hotel_id = ? AND r.active AND r.operational_status <> 'OUT_OF_SERVICE'
                  AND NOT EXISTS (
                      SELECT 1 FROM room_occupancies o
                      WHERE o.room_id = r.id AND o.stay && daterange(?, ?, '[)')
                        AND (o.kind <> 'HOLD' OR o.expires_at > now()))
                GROUP BY r.room_type_id""", rs -> {
            result.put(rs.getLong(1), rs.getLong(2));
        }, hotelId, checkIn, checkOut);
        return result;
    }

    /** Ocupaciones del calendario (reservaciones y bloqueos) que tocan [desde, hasta). */
    public List<Map<String, Object>> calendar(long hotelId, LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT o.room_id, o.kind, lower(o.stay) AS starts, upper(o.stay) AS ends,
                       res.id AS reservation_id, res.folio, res.contact_name, res.status,
                       m.reason
                FROM room_occupancies o
                LEFT JOIN reservations res ON res.id = o.reservation_id
                LEFT JOIN maintenance_records m ON m.id = o.maintenance_id
                WHERE o.hotel_id = ? AND o.stay && daterange(?, ?, '[)')
                  AND (o.kind <> 'HOLD' OR o.expires_at > now())
                ORDER BY o.room_id, lower(o.stay)""", hotelId, from, to);
    }
}
