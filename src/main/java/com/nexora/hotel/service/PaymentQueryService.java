package com.nexora.hotel.service;

import com.nexora.hotel.dto.catalog.PaymentDtos.MethodTotal;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentRow;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentsReport;
import com.nexora.hotel.dto.catalog.PaymentDtos.ReconciliationItem;
import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Consulta de movimientos de pago y conciliación. Los totales son de DINERO MOVIDO (cobrado menos devuelto) en el
 * periodo, distinto de lo vendido en reservaciones: nunca se suman como si fueran el mismo ingreso.
 */
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final JdbcTemplate jdbc;
    private final HotelDirectory hotelDirectory;

    @Transactional(readOnly = true)
    public PaymentsReport list(LocalDate from, LocalDate to, PaymentMethod method, PaymentStatus status, PaymentKind kind,
                               String query, int page, int size) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw new BusinessException("Elige un rango de fechas de hasta un año");
        }
        ZoneId zone = hotelDirectory.current().zoneId();
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        long hotelId = TenantContext.requireHotelId();

        StringBuilder where = new StringBuilder(" WHERE p.hotel_id = ? AND p.created_at >= ? AND p.created_at < ?");
        List<Object> args = new ArrayList<>(List.of(hotelId, Timestamp.from(start), Timestamp.from(end)));
        if (method != null) {
            where.append(" AND p.method = ?");
            args.add(method.name());
        }
        if (status != null) {
            where.append(" AND p.status = ?");
            args.add(status.name());
        }
        if (kind != null) {
            where.append(" AND p.kind = ?");
            args.add(kind.name());
        }
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase() + "%";
            where.append(" AND (lower(r.folio) LIKE ? OR lower(r.contact_name) LIKE ? OR lower(coalesce(p.reference, '')) LIKE ?)");
            args.addAll(List.of(like, like, like));
        }
        String from_ = " FROM payments p JOIN reservations r ON r.id = p.reservation_id LEFT JOIN users u ON u.id = p.created_by";
        long total = jdbc.queryForObject("SELECT count(*)" + from_ + where, Long.class, args.toArray());
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<PaymentRow> rows = jdbc.query("""
                SELECT p.id, p.reservation_id, r.folio, r.contact_name, p.kind, p.method, p.status, p.amount, p.reference,
                       p.card_last4, u.first_name || ' ' || u.last_name AS created_by_name, p.created_at, p.completed_at""" + from_ + where
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new PaymentRow(rs.getLong("id"), rs.getLong("reservation_id"), rs.getString("folio"),
                        rs.getString("contact_name"), PaymentKind.valueOf(rs.getString("kind")),
                        PaymentMethod.valueOf(rs.getString("method")), PaymentStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"), rs.getString("reference"), rs.getString("card_last4"),
                        rs.getString("created_by_name"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                pageArgs.toArray());

        // Totales del periodo: solo movimientos COMPLETADOS (con los mismos filtros de método y búsqueda).
        List<MethodTotal> totals = jdbc.query("""
                SELECT p.method,
                       coalesce(sum(p.amount) FILTER (WHERE p.kind = 'PAYMENT'), 0) AS received,
                       coalesce(sum(p.amount) FILTER (WHERE p.kind <> 'PAYMENT'), 0) AS returned,
                       count(*) AS n""" + from_ + where + " AND p.status = 'COMPLETED' GROUP BY p.method ORDER BY p.method",
                (rs, i) -> {
                    BigDecimal received = rs.getBigDecimal("received");
                    BigDecimal returned = rs.getBigDecimal("returned");
                    return new MethodTotal(PaymentMethod.valueOf(rs.getString("method")), received, returned,
                            received.subtract(returned), rs.getLong("n"));
                }, args.toArray());
        BigDecimal net = totals.stream().map(MethodTotal::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentsReport(rows, safePage, (int) Math.ceil(total / (double) safeSize), total, totals, net);
    }

    /**
     * Pendientes de conciliar: saldo a favor del huésped (reembolso pendiente), salidas o no-shows con saldo por cobrar,
     * y pagos en línea que llevan más de una hora sin resolverse.
     */
    @Transactional(readOnly = true)
    public List<ReconciliationItem> reconciliation() {
        long hotelId = TenantContext.requireHotelId();
        List<ReconciliationItem> items = new ArrayList<>(jdbc.query("""
                SELECT id, folio, contact_name, status, check_in, paid_total,
                       (CASE WHEN status IN ('CANCELLED','NO_SHOW') THEN penalty_amount WHEN status = 'EXPIRED' THEN 0 ELSE total END)
                         + charges_total AS due
                FROM reservations
                WHERE hotel_id = ?
                  AND ( paid_total > (CASE WHEN status IN ('CANCELLED','NO_SHOW') THEN penalty_amount WHEN status = 'EXPIRED' THEN 0 ELSE total END) + charges_total
                     OR (status IN ('CHECKED_OUT','NO_SHOW') AND paid_total < (CASE WHEN status = 'NO_SHOW' THEN penalty_amount ELSE total END) + charges_total))
                ORDER BY check_in DESC LIMIT 200""",
                (rs, i) -> {
                    BigDecimal due = rs.getBigDecimal("due");
                    BigDecimal paid = rs.getBigDecimal("paid_total");
                    BigDecimal balance = due.subtract(paid);
                    return new ReconciliationItem(rs.getLong("id"), rs.getString("folio"), rs.getString("contact_name"),
                            ReservationStatus.valueOf(rs.getString("status")), rs.getDate("check_in").toLocalDate(), due, paid, balance,
                            balance.signum() < 0 ? "Saldo a favor del huésped: procede reembolso" : "Saldo por cobrar después de la salida");
                }, hotelId));
        items.addAll(jdbc.query("""
                SELECT r.id, r.folio, r.contact_name, r.status, r.check_in, r.paid_total, p.amount
                FROM payments p JOIN reservations r ON r.id = p.reservation_id
                WHERE p.hotel_id = ? AND p.method = 'ONLINE' AND p.status = 'PENDING' AND p.created_at < now() - interval '1 hour'
                ORDER BY p.created_at""",
                (rs, i) -> new ReconciliationItem(rs.getLong("id"), rs.getString("folio"), rs.getString("contact_name"),
                        ReservationStatus.valueOf(rs.getString("status")), rs.getDate("check_in").toLocalDate(),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("paid_total"), rs.getBigDecimal("amount"),
                        "Pago en línea sin confirmar por la pasarela"), hotelId));
        return items;
    }
}
