package com.nexora.hotel.repository;

import com.nexora.hotel.domain.StayPricing;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Registros de solo agregar de una reservación: desglose de precio por revisión, historial de estados y tokens
 * de acceso del huésped. Siempre filtrados por hotel explícitamente (JDBC no pasa por el filtro de Hibernate).
 */
@Repository
@RequiredArgsConstructor
public class ReservationLedger {

    private final JdbcTemplate jdbc;

    public record PriceLine(int revision, String type, LocalDate date, String description, BigDecimal quantity,
                            BigDecimal unitAmount, BigDecimal amount) {
    }

    public record StatusChange(String from, String to, String reason, String userName, Instant at) {
    }

    public void savePriceLines(long hotelId, long reservationId, int revision, List<StayPricing.Line> lines) {
        jdbc.batchUpdate("""
                INSERT INTO reservation_price_lines (hotel_id, reservation_id, revision, line_type, stay_date, description,
                                                     quantity, unit_amount, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", lines, 100, (ps, l) -> {
            ps.setLong(1, hotelId);
            ps.setLong(2, reservationId);
            ps.setInt(3, revision);
            ps.setString(4, l.type().name());
            ps.setDate(5, l.date() == null ? null : Date.valueOf(l.date()));
            ps.setString(6, l.description());
            ps.setBigDecimal(7, l.quantity());
            ps.setBigDecimal(8, l.unitAmount());
            ps.setBigDecimal(9, l.amount());
        });
    }

    public List<PriceLine> priceLines(long hotelId, long reservationId) {
        return jdbc.query("""
                SELECT revision, line_type, stay_date, description, quantity, unit_amount, amount
                FROM reservation_price_lines WHERE hotel_id = ? AND reservation_id = ?
                ORDER BY revision DESC, id""", (rs, i) -> new PriceLine(rs.getInt(1), rs.getString(2),
                rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), rs.getString(4), rs.getBigDecimal(5),
                rs.getBigDecimal(6), rs.getBigDecimal(7)), hotelId, reservationId);
    }

    public void statusChange(long hotelId, long reservationId, String from, String to, String reason, Long userId) {
        jdbc.update("""
                INSERT INTO reservation_status_history (hotel_id, reservation_id, from_status, to_status, reason, user_id)
                VALUES (?, ?, ?, ?, ?, ?)""", hotelId, reservationId, from, to, reason, userId);
    }

    public List<StatusChange> history(long hotelId, long reservationId) {
        return jdbc.query("""
                SELECT h.from_status, h.to_status, h.reason, u.first_name || ' ' || u.last_name, h.created_at
                FROM reservation_status_history h LEFT JOIN users u ON u.id = h.user_id
                WHERE h.hotel_id = ? AND h.reservation_id = ? ORDER BY h.created_at, h.id""",
                (rs, i) -> new StatusChange(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), hotelId, reservationId);
    }

    public void saveAccessToken(long hotelId, long reservationId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO reservation_access_tokens (hotel_id, reservation_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)""", hotelId, reservationId, tokenHash, Timestamp.from(expiresAt));
    }

    /** Reservación del token si es vigente, del hotel indicado y coincide con el folio. */
    public Optional<Long> reservationForToken(long hotelId, String folio, String tokenHash) {
        return jdbc.query("""
                SELECT r.id FROM reservation_access_tokens t JOIN reservations r ON r.id = t.reservation_id
                WHERE t.hotel_id = ? AND r.folio = ? AND t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > now()""",
                (rs, i) -> rs.getLong(1), hotelId, folio, tokenHash).stream().findFirst();
    }
}
