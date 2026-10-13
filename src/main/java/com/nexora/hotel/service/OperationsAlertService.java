package com.nexora.hotel.service;

import com.nexora.hotel.entity.HotelSettings;
import com.nexora.hotel.notification.StaffNotificationType;
import com.nexora.hotel.notification.StaffNotifier;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Avisos de la operación del día, por hotel y en su hora local. Cada uno lleva una clave por día (o por
 * reservación), así que la tarea puede correr cada pocos minutos sin repetir nada.
 * <ul>
 *   <li>Llegadas de hoy: un resumen al abrir la recepción (no antes de las 7:00).</li>
 *   <li>Posibles no-shows: confirmadas cuya llegada ya pasó y nadie registró. Se avisa una vez por reservación.</li>
 *   <li>Salidas atrasadas: huéspedes en casa media hora después de la hora de salida del día que les toca.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OperationsAlertService {

    private static final LocalTime EARLIEST_MORNING = LocalTime.of(7, 0);
    private static final Duration LATE_CHECKOUT_GRACE = Duration.ofMinutes(30);
    /** Solo se buscan no-shows de la última semana: más atrás ya no es un aviso útil sino un pendiente de limpieza. */
    private static final int NO_SHOW_LOOKBACK_DAYS = 7;
    static final int RETENTION_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final HotelSettingsService settingsService;
    private final HotelDirectory hotelDirectory;
    private final StaffNotifier staffNotifier;
    private final Clock clock;

    @Transactional
    public void check() {
        long hotelId = TenantContext.requireHotelId();
        HotelSettings settings = settingsService.settings();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(hotelDirectory.current().zoneId()));
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();

        LocalTime morning = settings.getReceptionOpens().isAfter(EARLIEST_MORNING) ? settings.getReceptionOpens() : EARLIEST_MORNING;
        if (!time.isBefore(morning)) {
            arrivals(hotelId, today);
            noShows(hotelId, today);
        }
        LocalTime lateAfter = settings.getCheckOutTime().plus(LATE_CHECKOUT_GRACE);
        // Si la salida es tan tarde que la gracia pasa de medianoche, solo cuentan las salidas de días anteriores.
        boolean lateToday = lateAfter.isAfter(settings.getCheckOutTime()) && !time.isBefore(lateAfter);
        lateCheckouts(hotelId, lateToday ? today : today.minusDays(1), settings.getCheckOutTime());
    }

    private void arrivals(long hotelId, LocalDate today) {
        long count = Objects.requireNonNullElse(jdbc.queryForObject("""
                SELECT count(*) FROM reservations WHERE hotel_id = ? AND check_in = ?
                  AND status IN ('CONFIRMED','PENDING','PENDING_PAYMENT')""", Long.class, hotelId, today), 0L);
        if (count > 0) {
            staffNotifier.notify(StaffNotifier.Notice.of(StaffNotificationType.ARRIVALS_TODAY, "ARRIVALS:" + today)
                    .param("count", count).param("date", today).link("/panel/reservaciones"));
        }
    }

    private void noShows(long hotelId, LocalDate today) {
        jdbc.query("""
                SELECT id, folio, contact_name, check_in, check_out FROM reservations
                WHERE hotel_id = ? AND status = 'CONFIRMED' AND check_in < ? AND check_in >= ?
                ORDER BY check_in, id""", rs -> {
            long id = rs.getLong("id");
            staffNotifier.notify(StaffNotifier.Notice.of(StaffNotificationType.NO_SHOW_CANDIDATE, "NOSHOW:" + id)
                    .param("folio", rs.getString("folio")).param("guest", rs.getString("contact_name"))
                    .param("checkIn", rs.getDate("check_in").toLocalDate()).param("checkOut", rs.getDate("check_out").toLocalDate())
                    .link(StaffNotifier.reservationLink(id)).related("Reservation", id));
        }, hotelId, today, today.minusDays(NO_SHOW_LOOKBACK_DAYS));
    }

    /** En casa con salida en {@code upTo} o antes. Un aviso por reservación y día de retraso. */
    private void lateCheckouts(long hotelId, LocalDate upTo, LocalTime checkOutTime) {
        jdbc.query("""
                SELECT r.id, r.folio, r.contact_name, r.check_out,
                       (SELECT rm.number FROM reservation_rooms rr JOIN rooms rm ON rm.id = rr.room_id
                        WHERE rr.reservation_id = r.id ORDER BY rr.id LIMIT 1) AS room
                FROM reservations r
                WHERE r.hotel_id = ? AND r.status = 'IN_HOUSE' AND r.check_out <= ?
                ORDER BY r.check_out, r.id""", rs -> {
            long id = rs.getLong("id");
            staffNotifier.notify(StaffNotifier.Notice.of(StaffNotificationType.LATE_CHECKOUT, "LATE:" + id + ":" + upTo)
                    .param("folio", rs.getString("folio")).param("guest", rs.getString("contact_name"))
                    .param("room", rs.getString("room")).param("checkOut", rs.getDate("check_out").toLocalDate())
                    .param("time", checkOutTime).link(StaffNotifier.reservationLink(id)).related("Reservation", id));
        }, hotelId, upTo);
    }

    /** Los avisos viejos solo ocupan espacio: se borran a los {@value #RETENTION_DAYS} días (de todos los hoteles). */
    @Transactional
    public int purgeOld() {
        return jdbc.update("DELETE FROM staff_notifications WHERE created_at < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(RETENTION_DAYS))));
    }
}
