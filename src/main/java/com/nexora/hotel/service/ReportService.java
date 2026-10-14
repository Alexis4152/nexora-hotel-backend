package com.nexora.hotel.service;

import com.nexora.hotel.dto.catalog.ReportDtos.Chart;
import com.nexora.hotel.dto.catalog.ReportDtos.Column;
import com.nexora.hotel.dto.catalog.ReportDtos.Dashboard;
import com.nexora.hotel.dto.catalog.ReportDtos.Filters;
import com.nexora.hotel.dto.catalog.ReportDtos.Finance;
import com.nexora.hotel.dto.catalog.ReportDtos.Metric;
import com.nexora.hotel.dto.catalog.ReportDtos.Point;
import com.nexora.hotel.dto.catalog.ReportDtos.Report;
import com.nexora.hotel.dto.catalog.ReportDtos.ReportInfo;
import com.nexora.hotel.dto.catalog.ReportDtos.Rooms;
import com.nexora.hotel.dto.catalog.ReportDtos.Today;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reportes y dashboard. Solo lectura y siempre sobre datos reales del hotel actual (filtro explícito por hotel_id:
 * JDBC no pasa por el filtro de tenant de Hibernate). Las fechas se interpretan en la zona horaria del hotel.
 *
 * <p>Cada cifra declara qué representa, sin mezclar conceptos:
 * <ul>
 *   <li><b>Cobrado</b>: pagos completados menos reembolsos y reversos, por fecha en que se completaron.</li>
 *   <li><b>Anticipo</b>: pagos completados antes del día de llegada de su reservación.</li>
 *   <li><b>Devengado</b>: valor de las noches ocupadas en el periodo (total de la estancia repartido por noche), más
 *       cargos extra y penalizaciones aplicadas. No es dinero recibido.</li>
 *   <li><b>Reservado</b>: valor total de reservaciones vigentes, sin importar si se cobró.</li>
 *   <li><b>Por cobrar</b>: saldo pendiente de cuentas comprometidas (lo adeudado menos lo pagado).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final String OPERATIONAL = "REPORTS:OPERATIONAL";
    static final String FINANCIAL = "REPORTS:FINANCIAL";
    private static final int MAX_DAYS = 366;
    private static final int SCREEN_ROWS = 500;
    static final int EXPORT_ROWS = 20_000;
    private static final int FETCH_LIMIT = 50_000;
    private static final Locale MX = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Estados que ocupan habitación de verdad (vendidas). */
    private static final String SOLD = "('CONFIRMED','IN_HOUSE','CHECKED_OUT')";
    /** Cuentas con compromiso de pago: aquí el saldo sí es una deuda o un saldo a favor. */
    private static final String COMMITTED = "('CONFIRMED','IN_HOUSE','CHECKED_OUT','CANCELLED','NO_SHOW')";
    private static final String AMOUNT_DUE_SQL = """
            (CASE WHEN r.status IN ('CANCELLED','NO_SHOW') THEN r.penalty_amount WHEN r.status = 'EXPIRED' THEN 0
                  ELSE r.total END + r.charges_total)""";

    static final Map<String, String> RES_STATUS = ordered(
            "PENDING", "Pendiente", "PENDING_PAYMENT", "Esperando pago", "CONFIRMED", "Confirmada",
            "IN_HOUSE", "En estancia", "CHECKED_OUT", "Finalizada", "CANCELLED", "Cancelada",
            "NO_SHOW", "No se presentó", "EXPIRED", "Expirada");
    static final Map<String, String> SOURCE = ordered(
            "WEB", "Sitio web", "FRONT_DESK", "Recepción", "PHONE", "Teléfono", "EMAIL", "Correo", "OTHER", "Otro");
    static final Map<String, String> METHOD = ordered(
            "CASH", "Efectivo", "CARD", "Tarjeta (terminal)", "TRANSFER", "Transferencia", "ONLINE", "En línea");
    static final Map<String, String> KIND = ordered("PAYMENT", "Pago", "REFUND", "Reembolso", "REVERSAL", "Reverso");
    static final Map<String, String> PAY_STATUS = ordered(
            "PENDING", "Pendiente", "COMPLETED", "Completado", "FAILED", "Fallido", "CANCELLED", "Cancelado");
    static final Map<String, String> CASH_TYPE = ordered(
            "PAYMENT", "Cobro", "REFUND", "Reembolso", "REVERSAL", "Reverso", "WITHDRAWAL", "Retiro", "DEPOSIT", "Depósito");

    /** Catálogo: tipo → (título, descripción, grupo, financiero, foto actual, filtros). */
    private static final List<ReportInfo> CATALOG = List.of(
            new ReportInfo("reservations", "Reservaciones", "Reservaciones por fecha de creación o de llegada, por canal y estado.",
                    "Operación", false, false, List.of("basis", "status", "source", "roomTypeId")),
            new ReportInfo("occupancy", "Ocupación", "Habitaciones vendidas contra disponibles, noche por noche.",
                    "Operación", false, false, List.of("roomTypeId")),
            new ReportInfo("rooms", "Habitaciones más reservadas", "Noches vendidas y ocupación de cada habitación.",
                    "Operación", false, false, List.of("roomTypeId")),
            new ReportInfo("cancellations", "Cancelaciones", "Cancelaciones y no presentados, con su motivo y penalización.",
                    "Operación", false, false, List.of("source", "roomTypeId")),
            new ReportInfo("customers", "Clientes recurrentes", "Huéspedes con dos o más estancias que llegaron en el periodo.",
                    "Operación", false, false, List.of()),
            new ReportInfo("pending", "Reservaciones pendientes", "Solicitudes sin confirmar en este momento.",
                    "Operación", false, true, List.of("source")),
            new ReportInfo("income", "Ingresos por periodo", "Cobrado contra devengado, día por día, con anticipos.",
                    "Finanzas", true, false, List.of()),
            new ReportInfo("payments", "Pagos por método", "Pagos, reembolsos y reversos con su método y estado.",
                    "Finanzas", true, false, List.of("method", "kind", "status")),
            new ReportInfo("refunds", "Reembolsos", "Devoluciones y reversos registrados.",
                    "Finanzas", true, false, List.of("method")),
            new ReportInfo("receivables", "Cuentas por cobrar", "Saldos pendientes y saldos a favor de cada reservación.",
                    "Finanzas", true, true, List.of("status")),
            new ReportInfo("cash", "Movimientos de caja", "Entradas y salidas registradas en las cajas de recepción.",
                    "Finanzas", true, false, List.of("method")));

    private final HotelDirectory hotelDirectory;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ── Catálogo y permisos ───────────────────────────────────────────────────────────────

    public List<ReportInfo> catalog() {
        return CATALOG.stream().filter(i -> can(i.financial() ? FINANCIAL : OPERATIONAL)).toList();
    }

    public ReportInfo info(String type) {
        ReportInfo info = CATALOG.stream().filter(i -> i.type().equals(type)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Reporte no encontrado"));
        if (!can(info.financial() ? FINANCIAL : OPERATIONAL)) {
            throw new AccessDeniedException("Sin permiso para el reporte " + type);
        }
        return info;
    }

    private static boolean can(String permission) {
        return SecurityUtils.currentPrincipal().map(p -> p.hasPermission(permission)).orElse(false);
    }

    // ── Reportes ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Report report(String type, Filters raw, boolean forExport) {
        ReportInfo info = info(type);
        Hotel hotel = hotelDirectory.current();
        Ctx c = new Ctx(TenantContext.requireHotelId(), hotel.zoneId(), hotel.getCurrency(), normalize(raw, hotel.zoneId()),
                can(FINANCIAL), can("CUSTOMERS:VIEW"), forExport ? EXPORT_ROWS : SCREEN_ROWS, info);
        return switch (type) {
            case "reservations" -> reservations(c);
            case "occupancy" -> occupancy(c);
            case "rooms" -> rooms(c);
            case "cancellations" -> cancellations(c);
            case "customers" -> customers(c);
            case "pending" -> pending(c);
            case "income" -> income(c);
            case "payments" -> payments(c, false);
            case "refunds" -> payments(c, true);
            case "receivables" -> receivables(c);
            case "cash" -> cash(c);
            default -> throw new ResourceNotFoundException("Reporte no encontrado");
        };
    }

    private Filters normalize(Filters f, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate from = f.from() != null ? f.from() : today.withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : today;
        if (to.isBefore(from)) {
            throw new BusinessException("La fecha final no puede ser anterior a la inicial");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException("El periodo no puede exceder " + MAX_DAYS + " días");
        }
        return new Filters(from, to, code(f.status(), RES_STATUS.keySet(), PAY_STATUS.keySet()),
                code(f.source(), SOURCE.keySet()), f.roomTypeId(), code(f.method(), METHOD.keySet()),
                code(f.kind(), KIND.keySet()), f.basis() == null ? null : f.basis().trim().toLowerCase(Locale.ROOT));
    }

    /** Solo se aceptan códigos conocidos: un valor libre nunca llega al SQL. */
    @SafeVarargs
    private static String code(String value, Set<String>... allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (Set<String> set : allowed) {
            if (set.contains(v)) {
                return v;
            }
        }
        throw new BusinessException("Filtro no válido: " + value);
    }

    private Report reservations(Ctx c) {
        boolean byArrival = "arrival".equals(c.f.basis());
        Sql q = new Sql("""
                SELECT r.folio, r.created_at, r.check_in, r.check_out, r.nights, r.contact_name, r.source, r.status,
                       r.adults + r.children AS guests, r.total, r.paid_total,
                       (SELECT string_agg(DISTINCT rt.name, ', ') FROM reservation_rooms rr JOIN room_types rt ON rt.id = rr.room_type_id
                         WHERE rr.reservation_id = r.id) AS room_types
                FROM reservations r WHERE r.hotel_id = ?""", c.hotelId);
        if (byArrival) {
            q.and("r.check_in BETWEEN ? AND ?", c.f.from(), c.f.to());
        } else {
            q.and("r.created_at >= ? AND r.created_at < ?", c.start(), c.end());
        }
        q.andIf(c.f.status(), "r.status = ?").andIf(c.f.source(), "r.source = ?");
        roomTypeFilter(q, c, "r.id");
        q.add(byArrival ? " ORDER BY r.check_in, r.id" : " ORDER BY r.created_at, r.id");

        List<Map<String, Object>> rows = q.query(jdbc, rs -> row(
                "folio", rs.getString("folio"),
                "createdAt", c.dateTime(rs, "created_at"),
                "checkIn", rs.getDate("check_in").toLocalDate().toString(),
                "checkOut", rs.getDate("check_out").toLocalDate().toString(),
                "nights", rs.getInt("nights"),
                "guests", rs.getInt("guests"),
                "guest", rs.getString("contact_name"),
                "roomTypes", rs.getString("room_types"),
                "source", SOURCE.get(rs.getString("source")),
                "status", RES_STATUS.get(rs.getString("status")),
                "_status", rs.getString("status"),
                "_created", c.date(rs, "created_at"),
                "total", rs.getBigDecimal("total"),
                "paid", rs.getBigDecimal("paid_total")));

        List<Map<String, Object>> live = rows.stream().filter(r -> isLive((String) r.get("_status"))).toList();
        long cancelled = rows.stream().filter(r -> Set.of("CANCELLED", "NO_SHOW").contains(r.get("_status"))).count();
        long pendingCount = rows.stream().filter(r -> Set.of("PENDING", "PENDING_PAYMENT").contains(r.get("_status"))).count();
        int nights = live.stream().mapToInt(r -> (int) r.get("nights")).sum();
        double avgLead = live.stream().mapToLong(r -> ChronoUnit.DAYS.between((LocalDate) r.get("_created"),
                LocalDate.parse((String) r.get("checkIn")))).average().orElse(0);

        List<Metric> metrics = new ArrayList<>(List.of(
                metric("count", "Reservaciones", rows.size(), "count", byArrival ? "Con llegada en el periodo" : "Creadas en el periodo"),
                metric("live", "Vigentes o realizadas", live.size(), "count", "Confirmadas, en estancia y finalizadas"),
                metric("pending", "Pendientes", pendingCount, "count", "Sin confirmar todavía"),
                metric("cancelled", "Canceladas / no show", cancelled, "count", null),
                metric("nights", "Noches reservadas", nights, "count", "De reservaciones vigentes o realizadas"),
                metric("lead", "Anticipación promedio", round1(avgLead), "number", "Días entre la reservación y la llegada")));
        if (c.financial) {
            metrics.add(metric("booked", "Importe reservado", sum(live, "total"), "money",
                    "Valor de las reservaciones vigentes o realizadas. No es dinero cobrado"));
        }
        String dateKey = byArrival ? "checkIn" : "_created";
        Chart perDay = dailyChart(c, "perDay", byArrival ? "Llegadas por día" : "Reservaciones creadas por día", "count",
                List.of("Reservaciones"), rows, r -> List.of(1), r -> dateKey.equals("checkIn")
                        ? LocalDate.parse((String) r.get("checkIn")) : (LocalDate) r.get("_created"));
        List<Column> cols = new ArrayList<>(List.of(col("folio", "Folio"), col("createdAt", "Creada", "datetime"),
                col("checkIn", "Llegada", "date"), col("checkOut", "Salida", "date"), col("nights", "Noches", "count"),
                col("guests", "Huéspedes", "count"), col("guest", "Titular"), col("roomTypes", "Habitación"),
                col("source", "Canal"), col("status", "Estado")));
        if (c.financial) {
            cols.add(col("total", "Total", "money"));
            cols.add(col("paid", "Pagado", "money"));
        }
        return c.build(byArrival ? "Por fecha de llegada" : "Por fecha de creación", metrics,
                List.of(perDay, countChart("bySource", "Por canal", rows, "source", SOURCE.values()),
                        countChart("byStatus", "Por estado", rows, "status", RES_STATUS.values())),
                cols, rows, List.of());
    }

    private Report occupancy(Ctx c) {
        List<Object> args = new ArrayList<>();
        String typeCond = "";
        if (c.f.roomTypeId() != null) {
            typeCond = " AND rm.room_type_id = ?";
        }
        String sql = """
                SELECT d::date AS night,
                  (SELECT count(*) FROM rooms rm WHERE rm.hotel_id = ? AND rm.active%1$s) AS rooms,
                  (SELECT count(DISTINCT o.room_id) FROM room_occupancies o JOIN rooms rm ON rm.id = o.room_id
                    WHERE o.hotel_id = ? AND o.kind = 'BLOCK' AND rm.active AND o.stay @> d::date%1$s) AS blocked,
                  (SELECT count(DISTINCT o.room_id) FROM room_occupancies o JOIN rooms rm ON rm.id = o.room_id
                    JOIN reservations r ON r.id = o.reservation_id
                    WHERE o.hotel_id = ? AND o.kind = 'RESERVATION' AND r.status IN %2$s AND o.stay @> d::date%1$s) AS sold
                FROM generate_series(?::date, ?::date, interval '1 day') d ORDER BY 1""".formatted(typeCond, SOLD);
        for (int i = 0; i < 3; i++) {
            args.add(c.hotelId);
            if (c.f.roomTypeId() != null) {
                args.add(c.f.roomTypeId());
            }
        }
        args.add(c.f.from());
        args.add(c.f.to());
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, i) -> {
            LocalDate night = rs.getDate("night").toLocalDate();
            long rooms = rs.getLong("rooms");
            long blocked = rs.getLong("blocked");
            long sold = rs.getLong("sold");
            long sellable = Math.max(0, rooms - blocked);
            return row("night", night.toString(),
                    "weekday", capitalize(night.getDayOfWeek().getDisplayName(TextStyle.FULL, MX)),
                    "rooms", rooms, "blocked", blocked, "sellable", sellable, "sold", sold,
                    "free", Math.max(0, sellable - sold), "occupancy", pct(sold, sellable));
        }, args.toArray());

        long sold = rows.stream().mapToLong(r -> (long) r.get("sold")).sum();
        long sellable = rows.stream().mapToLong(r -> (long) r.get("sellable")).sum();
        Map<String, Object> peak = rows.stream().max((a, b) -> Double.compare((double) a.get("occupancy"),
                (double) b.get("occupancy"))).orElse(null);
        List<Metric> metrics = List.of(
                metric("occupancy", "Ocupación promedio", pct(sold, sellable), "percent", "Noches vendidas ÷ noches disponibles"),
                metric("sold", "Noches vendidas", sold, "count", "Habitación-noche con reservación confirmada, en estancia o finalizada"),
                metric("sellable", "Noches disponibles", sellable, "count", "Habitaciones activas menos bloqueos por mantenimiento"),
                metric("peak", "Noche de mayor ocupación", peak == null ? "—" : peak.get("night"), "date",
                        peak == null ? null : peak.get("occupancy") + " %"));
        Chart chart = new Chart("occupancy", "Ocupación por noche", "line", "percent", List.of("Ocupación"),
                rows.stream().map(r -> new Point((String) r.get("night"), List.of((Number) r.get("occupancy")))).toList());
        return c.build("Por noche de estancia", metrics, List.of(chart),
                List.of(col("night", "Noche", "date"), col("weekday", "Día"), col("rooms", "Habitaciones", "count"),
                        col("blocked", "Bloqueadas", "count"), col("sellable", "Disponibles", "count"),
                        col("sold", "Vendidas", "count"), col("free", "Libres", "count"), col("occupancy", "Ocupación", "percent")),
                rows, List.of("Las habitaciones se cuentan según su estado actual (activas). Un bloqueo de mantenimiento resta esa noche."));
    }

    private Report rooms(Ctx c) {
        Sql q = new Sql("""
                SELECT rm.number, rt.name AS type_name, count(DISTINCT o.reservation_id) AS reservations,
                       coalesce(sum(upper(o.stay * x.rng) - lower(o.stay * x.rng)), 0) AS nights,
                       (SELECT coalesce(sum(upper(b.stay * x.rng) - lower(b.stay * x.rng)), 0) FROM room_occupancies b
                         WHERE b.room_id = rm.id AND b.kind = 'BLOCK' AND b.stay && x.rng) AS blocked
                FROM rooms rm JOIN room_types rt ON rt.id = rm.room_type_id
                CROSS JOIN (SELECT daterange(?::date, ?::date, '[)') AS rng) x
                LEFT JOIN (room_occupancies o JOIN reservations r ON r.id = o.reservation_id AND r.status IN %s)
                       ON o.room_id = rm.id AND o.kind = 'RESERVATION' AND o.stay && x.rng
                WHERE rm.hotel_id = ? AND rm.active""".formatted(SOLD), c.f.from(), c.f.to().plusDays(1), c.hotelId);
        q.andIf(c.f.roomTypeId(), "rm.room_type_id = ?");
        q.add(" GROUP BY rm.id, rm.number, rt.name, x.rng ORDER BY nights DESC, reservations DESC, rm.number");
        long days = c.days();
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            long nights = rs.getLong("nights");
            long available = Math.max(0, days - rs.getLong("blocked"));
            return row("room", rs.getString("number"), "type", rs.getString("type_name"),
                    "reservations", rs.getLong("reservations"), "nights", nights, "available", available,
                    "occupancy", pct(nights, available));
        });
        Map<String, Long> byType = new LinkedHashMap<>();
        rows.forEach(r -> byType.merge((String) r.get("type"), (Long) r.get("nights"), Long::sum));
        List<Point> typePoints = byType.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new Point(e.getKey(), List.of(e.getValue()))).toList();
        Map<String, Object> top = rows.isEmpty() ? null : rows.get(0);
        long totalNights = rows.stream().mapToLong(r -> (long) r.get("nights")).sum();
        long totalAvail = rows.stream().mapToLong(r -> (long) r.get("available")).sum();
        List<Metric> metrics = List.of(
                metric("top", "Más reservada", top == null || (long) top.get("nights") == 0 ? "—" : "Hab. " + top.get("room"),
                        "text", top == null ? null : top.get("nights") + " noches · " + top.get("type")),
                metric("topType", "Tipo más vendido", typePoints.isEmpty() || typePoints.get(0).values().get(0).longValue() == 0
                        ? "—" : typePoints.get(0).label(), "text", null),
                metric("nights", "Noches vendidas", totalNights, "count", "Dentro del periodo"),
                metric("occupancy", "Ocupación del inventario", pct(totalNights, totalAvail), "percent", null));
        return c.build("Noches vendidas dentro del periodo", metrics,
                List.of(new Chart("byType", "Noches vendidas por tipo", "bar", "count", List.of("Noches"), typePoints)),
                List.of(col("room", "Habitación"), col("type", "Tipo"), col("reservations", "Reservaciones", "count"),
                        col("nights", "Noches vendidas", "count"), col("available", "Noches disponibles", "count"),
                        col("occupancy", "Ocupación", "percent")), rows, List.of());
    }

    private Report cancellations(Ctx c) {
        Sql q = new Sql("""
                SELECT r.folio, r.contact_name, r.status, coalesce(r.cancelled_at, r.no_show_at) AS at, r.check_in,
                       r.nights, r.source, r.cancel_reason, r.penalty_amount, r.total
                FROM reservations r WHERE r.hotel_id = ?
                  AND ((r.status = 'CANCELLED' AND r.cancelled_at >= ? AND r.cancelled_at < ?)
                    OR (r.status = 'NO_SHOW' AND r.no_show_at >= ? AND r.no_show_at < ?))""",
                c.hotelId, c.start(), c.end(), c.start(), c.end());
        q.andIf(c.f.source(), "r.source = ?");
        roomTypeFilter(q, c, "r.id");
        q.add(" ORDER BY at, r.id");
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
            LocalDate checkIn = rs.getDate("check_in").toLocalDate();
            return row("folio", rs.getString("folio"), "guest", rs.getString("contact_name"),
                    "type", "NO_SHOW".equals(rs.getString("status")) ? "No se presentó" : "Cancelada",
                    "_status", rs.getString("status"),
                    "at", c.dateTime(at), "_date", at.atZoneSameInstant(c.zone).toLocalDate(),
                    "checkIn", checkIn.toString(), "nights", rs.getInt("nights"),
                    "notice", ChronoUnit.DAYS.between(at.atZoneSameInstant(c.zone).toLocalDate(), checkIn),
                    "source", SOURCE.get(rs.getString("source")), "reason", rs.getString("cancel_reason"),
                    "penalty", rs.getBigDecimal("penalty_amount"), "total", rs.getBigDecimal("total"));
        });
        long noShows = rows.stream().filter(r -> "NO_SHOW".equals(r.get("_status"))).count();
        long cancelled = rows.size() - noShows;
        Sql created = new Sql("SELECT count(*) FROM reservations r WHERE r.hotel_id = ? AND r.created_at >= ? AND r.created_at < ?",
                c.hotelId, c.start(), c.end());
        created.andIf(c.f.source(), "r.source = ?");
        roomTypeFilter(created, c, "r.id");
        Long createdCount = jdbc.queryForObject(created.sql(), Long.class, created.args());
        List<Map<String, Object>> onlyCancelled = rows.stream().filter(r -> "CANCELLED".equals(r.get("_status"))).toList();
        double avgNotice = onlyCancelled.stream().mapToLong(r -> (long) r.get("notice")).average().orElse(0);

        List<Metric> metrics = new ArrayList<>(List.of(
                metric("cancelled", "Cancelaciones", cancelled, "count", "Por fecha de cancelación"),
                metric("noShow", "No se presentaron", noShows, "count", "Por fecha en que se marcó"),
                metric("rate", "Tasa de cancelación", pct(cancelled, createdCount == null ? 0 : createdCount), "percent",
                        "Cancelaciones ÷ reservaciones creadas en el mismo periodo"),
                metric("notice", "Anticipación promedio", round1(avgNotice), "number", "Días entre la cancelación y la llegada"),
                metric("lostNights", "Noches liberadas", rows.stream().mapToInt(r -> (int) r.get("nights")).sum(), "count", null)));
        if (c.financial) {
            metrics.add(metric("penalties", "Penalizaciones aplicadas", sum(rows, "penalty"), "money",
                    "Según la política aceptada. Lo efectivamente cobrado aparece en Pagos"));
        }
        List<Column> cols = new ArrayList<>(List.of(col("folio", "Folio"), col("guest", "Titular"), col("type", "Tipo"),
                col("at", "Fecha", "datetime"), col("checkIn", "Llegada", "date"), col("notice", "Días de anticipación", "count"),
                col("nights", "Noches", "count"), col("source", "Canal"), col("reason", "Motivo")));
        if (c.financial) {
            cols.add(col("total", "Importe de la reservación", "money"));
            cols.add(col("penalty", "Penalización", "money"));
        }
        return c.build("Por fecha de cancelación", metrics,
                List.of(dailyChart(c, "perDay", "Cancelaciones por día", "count", List.of("Canceladas", "No show"), rows,
                                r -> "NO_SHOW".equals(r.get("_status")) ? List.of(0, 1) : List.of(1, 0), r -> (LocalDate) r.get("_date")),
                        countChart("bySource", "Por canal", rows, "source", SOURCE.values())),
                cols, rows, List.of("Las reservaciones que expiraron sin pago no se cuentan como cancelaciones."));
    }

    private Report customers(Ctx c) {
        Sql q = new Sql("""
                SELECT c.first_name || ' ' || c.last_name AS name, c.email, c.phone_country_code, c.phone, c.country,
                       count(r.id) AS stays, sum(r.nights) AS nights, min(r.check_in) AS first_stay, max(r.check_in) AS last_stay,
                       sum(r.total + r.charges_total) AS spent
                FROM customers c JOIN reservations r ON r.customer_id = c.id AND r.status IN %s
                WHERE c.hotel_id = ? AND c.anonymized_at IS NULL
                GROUP BY c.id HAVING count(r.id) >= 2 AND bool_or(r.check_in BETWEEN ? AND ?)
                ORDER BY stays DESC, last_stay DESC""".formatted(SOLD), c.hotelId, c.f.from(), c.f.to());
        List<Map<String, Object>> rows = q.query(jdbc, rs -> row("name", rs.getString("name"),
                "email", c.customers ? rs.getString("email") : null,
                "phone", c.customers && rs.getString("phone") != null ? "+" + rs.getString("phone_country_code") + " " + rs.getString("phone") : null,
                "country", rs.getString("country"), "stays", rs.getLong("stays"), "nights", rs.getLong("nights"),
                "firstStay", rs.getDate("first_stay").toLocalDate().toString(),
                "lastStay", rs.getDate("last_stay").toLocalDate().toString(), "spent", rs.getBigDecimal("spent")));
        Long guests = jdbc.queryForObject("""
                SELECT count(DISTINCT r.customer_id) FROM reservations r
                WHERE r.hotel_id = ? AND r.status IN %s AND r.check_in BETWEEN ? AND ?""".formatted(SOLD),
                Long.class, c.hotelId, c.f.from(), c.f.to());
        List<Metric> metrics = new ArrayList<>(List.of(
                metric("recurring", "Clientes recurrentes", rows.size(), "count", "Dos o más estancias, con llegada en el periodo"),
                metric("share", "Del total de huéspedes", pct(rows.size(), guests == null ? 0 : guests), "percent",
                        "Recurrentes ÷ clientes con llegada en el periodo"),
                metric("avgStays", "Estancias promedio", round1(rows.stream().mapToLong(r -> (long) r.get("stays")).average().orElse(0)),
                        "number", "Historial completo de cada cliente")));
        if (c.financial) {
            metrics.add(metric("spent", "Valor de sus estancias", sum(rows, "spent"), "money",
                    "Total de estancias y cargos (historial completo). No es lo cobrado"));
        }
        List<Column> cols = new ArrayList<>(List.of(col("name", "Cliente")));
        if (c.customers) {
            cols.add(col("email", "Correo"));
            cols.add(col("phone", "Teléfono"));
        }
        cols.addAll(List.of(col("country", "País"), col("stays", "Estancias", "count"), col("nights", "Noches", "count"),
                col("firstStay", "Primera llegada", "date"), col("lastStay", "Última llegada", "date")));
        if (c.financial) {
            cols.add(col("spent", "Valor de estancias", "money"));
        }
        return c.build("Clientes con llegada en el periodo", metrics, List.of(), cols, rows,
                List.of("Se cuentan estancias confirmadas, en curso o finalizadas. Los clientes anonimizados no aparecen."));
    }

    private Report pending(Ctx c) {
        Instant now = clock.instant();
        Sql q = new Sql("""
                SELECT r.folio, r.created_at, r.check_in, r.check_out, r.contact_name, r.source, r.status, r.expires_at,
                       r.total, r.deposit_required, r.paid_total
                FROM reservations r WHERE r.hotel_id = ? AND r.status IN ('PENDING','PENDING_PAYMENT')""", c.hotelId);
        q.andIf(c.f.source(), "r.source = ?");
        q.add(" ORDER BY r.check_in, r.id");
        LocalDate today = LocalDate.now(clock.withZone(c.zone));
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
            return row("folio", rs.getString("folio"), "createdAt", c.dateTime(rs, "created_at"),
                    "checkIn", rs.getDate("check_in").toLocalDate().toString(),
                    "checkOut", rs.getDate("check_out").toLocalDate().toString(), "guest", rs.getString("contact_name"),
                    "source", SOURCE.get(rs.getString("source")), "status", RES_STATUS.get(rs.getString("status")),
                    "expiresAt", expires == null ? null : c.dateTime(expires),
                    "_expires", expires == null ? null : expires.toInstant(),
                    "total", rs.getBigDecimal("total"), "deposit", rs.getBigDecimal("deposit_required"),
                    "paid", rs.getBigDecimal("paid_total"));
        });
        long soon = rows.stream().filter(r -> !LocalDate.parse((String) r.get("checkIn")).isAfter(today.plusDays(7))).count();
        long expiring = rows.stream().filter(r -> r.get("_expires") != null
                && ((Instant) r.get("_expires")).isBefore(now.plus(24, ChronoUnit.HOURS))).count();
        List<Metric> metrics = new ArrayList<>(List.of(
                metric("count", "Pendientes", rows.size(), "count", "Solicitudes y reservaciones esperando pago"),
                metric("soon", "Llegan en 7 días o menos", soon, "count", "Conviene confirmarlas o contactar al huésped"),
                metric("expiring", "Expiran en 24 h", expiring, "count", "Se liberan automáticamente si no se pagan")));
        if (c.financial) {
            metrics.add(metric("value", "Importe en espera", sum(rows, "total"), "money", "Valor de estas reservaciones. Aún no es venta"));
        }
        List<Column> cols = new ArrayList<>(List.of(col("folio", "Folio"), col("createdAt", "Creada", "datetime"),
                col("checkIn", "Llegada", "date"), col("checkOut", "Salida", "date"), col("guest", "Titular"),
                col("source", "Canal"), col("status", "Estado"), col("expiresAt", "Expira", "datetime")));
        if (c.financial) {
            cols.addAll(List.of(col("total", "Total", "money"), col("deposit", "Anticipo requerido", "money"), col("paid", "Pagado", "money")));
        }
        return c.build("Situación actual (no depende del periodo)", metrics,
                List.of(countChart("bySource", "Por canal", rows, "source", SOURCE.values())), cols, rows, List.of());
    }

    private Report income(Ctx c) {
        TreeMap<LocalDate, BigDecimal[]> days = new TreeMap<>();
        for (LocalDate d = c.f.from(); !d.isAfter(c.f.to()); d = d.plusDays(1)) {
            days.put(d, zeros(5)); // cobrado, anticipos, hospedaje devengado, cargos, penalizaciones
        }
        jdbc.query("""
                SELECT p.kind, p.amount, p.completed_at, r.check_in FROM payments p JOIN reservations r ON r.id = p.reservation_id
                WHERE p.hotel_id = ? AND p.status = 'COMPLETED' AND p.completed_at >= ? AND p.completed_at < ?""", (RowCallbackHandler) rs -> {
            LocalDate day = rs.getObject("completed_at", OffsetDateTime.class).atZoneSameInstant(c.zone).toLocalDate();
            BigDecimal amount = rs.getBigDecimal("amount");
            boolean inflow = "PAYMENT".equals(rs.getString("kind"));
            BigDecimal[] v = days.get(day);
            if (v == null) {
                return;
            }
            v[0] = v[0].add(inflow ? amount : amount.negate());
            if (inflow && day.isBefore(rs.getDate("check_in").toLocalDate())) {
                v[1] = v[1].add(amount);
            }
        }, c.hotelId, c.start(), c.end());
        jdbc.query("""
                SELECT r.check_in, r.check_out, r.nights, r.total FROM reservations r
                WHERE r.hotel_id = ? AND r.status IN %s AND r.check_in <= ? AND r.check_out > ?""".formatted(SOLD), (RowCallbackHandler) rs -> {
            BigDecimal perNight = rs.getBigDecimal("total").divide(BigDecimal.valueOf(rs.getInt("nights")), 6, RoundingMode.HALF_UP);
            LocalDate end = rs.getDate("check_out").toLocalDate();
            for (LocalDate d = rs.getDate("check_in").toLocalDate(); d.isBefore(end); d = d.plusDays(1)) {
                BigDecimal[] v = days.get(d);
                if (v != null) {
                    v[2] = v[2].add(perNight);
                }
            }
        }, c.hotelId, c.f.to(), c.f.from());
        jdbc.query("""
                SELECT ch.amount, ch.created_at FROM reservation_charges ch
                WHERE ch.hotel_id = ? AND ch.voided_at IS NULL AND ch.created_at >= ? AND ch.created_at < ?""", (RowCallbackHandler) rs -> {
            BigDecimal[] v = days.get(rs.getObject("created_at", OffsetDateTime.class).atZoneSameInstant(c.zone).toLocalDate());
            if (v != null) {
                v[3] = v[3].add(rs.getBigDecimal("amount"));
            }
        }, c.hotelId, c.start(), c.end());
        jdbc.query("""
                SELECT r.penalty_amount, coalesce(r.cancelled_at, r.no_show_at) AS at FROM reservations r
                WHERE r.hotel_id = ? AND r.penalty_amount > 0 AND ((r.status = 'CANCELLED' AND r.cancelled_at >= ? AND r.cancelled_at < ?)
                   OR (r.status = 'NO_SHOW' AND r.no_show_at >= ? AND r.no_show_at < ?))""", (RowCallbackHandler) rs -> {
            BigDecimal[] v = days.get(rs.getObject("at", OffsetDateTime.class).atZoneSameInstant(c.zone).toLocalDate());
            if (v != null) {
                v[4] = v[4].add(rs.getBigDecimal("penalty_amount"));
            }
        }, c.hotelId, c.start(), c.end(), c.start(), c.end());

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal[] t = zeros(5);
        days.forEach((d, v) -> {
            for (int i = 0; i < 5; i++) {
                v[i] = money(v[i]);
                t[i] = t[i].add(v[i]);
            }
            rows.add(row("day", d.toString(), "collected", v[0], "deposits", v[1], "lodging", v[2], "charges", v[3],
                    "penalties", v[4], "accrued", v[2].add(v[3]).add(v[4])));
        });
        BigDecimal accrued = t[2].add(t[3]).add(t[4]);
        BigDecimal[] recv = receivableTotals(c.hotelId);
        List<Metric> metrics = List.of(
                metric("collected", "Cobrado neto", t[0], "money", "Pagos completados menos reembolsos y reversos"),
                metric("deposits", "Anticipos recibidos", t[1], "money", "Parte de lo cobrado que se pagó antes de la llegada"),
                metric("accrued", "Devengado", accrued, "money", "Noches ocupadas en el periodo + cargos extra + penalizaciones. No es dinero cobrado"),
                metric("lodging", "Hospedaje devengado", t[2], "money", "Total de cada estancia repartido entre sus noches (incluye impuestos)"),
                metric("charges", "Cargos extra", t[3], "money", "Consumos y servicios cargados a la cuenta (sin anulados)"),
                metric("receivable", "Por cobrar hoy", recv[0], "money", "Saldo pendiente de cuentas comprometidas, a la fecha de hoy"));
        Chart chart = new Chart("collectedVsAccrued", "Cobrado contra devengado", "bar", "money",
                List.of("Cobrado neto", "Devengado"),
                rows.stream().map(r -> new Point((String) r.get("day"), List.of((Number) r.get("collected"), (Number) r.get("accrued")))).toList());
        return c.build("Cobrado por fecha de pago · devengado por noche de estancia", metrics, List.of(chart),
                List.of(col("day", "Día", "date"), col("collected", "Cobrado neto", "money"), col("deposits", "De ello, anticipos", "money"),
                        col("lodging", "Hospedaje devengado", "money"), col("charges", "Cargos extra", "money"),
                        col("penalties", "Penalizaciones", "money"), col("accrued", "Total devengado", "money")),
                rows, List.of("Cobrado y devengado son conceptos distintos y no deben sumarse entre sí.",
                        "Un anticipo cobrado hoy se devenga en las noches de la estancia."));
    }

    private Report payments(Ctx c, boolean refundsOnly) {
        Sql q = new Sql("""
                SELECT coalesce(p.completed_at, p.created_at) AS at, r.folio, r.contact_name, p.kind, p.method, p.status,
                       p.amount, p.reference, p.card_brand, p.card_last4, p.provider, p.notes,
                       nullif(trim(coalesce(u.first_name, '') || ' ' || coalesce(u.last_name, '')), '') AS user_name
                FROM payments p JOIN reservations r ON r.id = p.reservation_id LEFT JOIN users u ON u.id = p.created_by
                WHERE p.hotel_id = ? AND coalesce(p.completed_at, p.created_at) >= ? AND coalesce(p.completed_at, p.created_at) < ?""",
                c.hotelId, c.start(), c.end());
        String status = c.f.status() == null ? "COMPLETED" : c.f.status();
        if (!PAY_STATUS.containsKey(status)) {
            throw new BusinessException("Filtro no válido: " + status);
        }
        q.and("p.status = ?", status);
        q.andIf(c.f.method(), "p.method = ?");
        if (refundsOnly) {
            q.add(" AND p.kind IN ('REFUND','REVERSAL')");
        } else {
            q.andIf(c.f.kind(), "p.kind = ?");
        }
        q.add(" ORDER BY at, p.id");
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
            boolean inflow = "PAYMENT".equals(rs.getString("kind"));
            BigDecimal amount = rs.getBigDecimal("amount");
            String card = rs.getString("card_last4") == null ? null
                    : (rs.getString("card_brand") == null ? "" : rs.getString("card_brand") + " ") + "•" + rs.getString("card_last4");
            return row("at", c.dateTime(at), "_date", at.atZoneSameInstant(c.zone).toLocalDate(),
                    "folio", rs.getString("folio"), "guest", rs.getString("contact_name"),
                    "kind", KIND.get(rs.getString("kind")), "_kind", rs.getString("kind"),
                    "method", METHOD.get(rs.getString("method")), "status", PAY_STATUS.get(rs.getString("status")),
                    "amount", inflow ? amount : amount.negate(),
                    "reference", firstNonBlank(rs.getString("reference"), card), "user", rs.getString("user_name"));
        });
        BigDecimal in = sumWhere(rows, r -> "PAYMENT".equals(r.get("_kind")));
        BigDecimal refunds = sumWhere(rows, r -> "REFUND".equals(r.get("_kind"))).negate();
        BigDecimal reversals = sumWhere(rows, r -> "REVERSAL".equals(r.get("_kind"))).negate();
        boolean completed = "COMPLETED".equals(status);
        String suffix = completed ? "" : " (" + PAY_STATUS.get(status).toLowerCase(MX) + "s)";
        List<Metric> metrics = new ArrayList<>();
        if (!refundsOnly) {
            metrics.add(metric("in", "Pagos recibidos" + suffix, in, "money", completed ? "Entradas de dinero completadas" : null));
        }
        metrics.add(metric("refunds", "Reembolsos" + suffix, refunds, "money", "Devoluciones al huésped"));
        metrics.add(metric("reversals", "Reversos" + suffix, reversals, "money", "Correcciones de pagos registrados por error"));
        if (!refundsOnly) {
            metrics.add(metric("net", completed ? "Cobrado neto" : "Neto" + suffix, in.subtract(refunds).subtract(reversals), "money",
                    completed ? "Recibido menos devoluciones y reversos" : null));
        }
        metrics.add(metric("count", "Operaciones", rows.size(), "count", null));

        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        METHOD.values().forEach(m -> byMethod.put(m, BigDecimal.ZERO));
        rows.forEach(r -> byMethod.merge((String) r.get("method"), (BigDecimal) r.get("amount"), BigDecimal::add));
        Chart methodChart = new Chart("byMethod", refundsOnly ? "Devoluciones por método" : "Neto por método", "bar", "money",
                List.of("Importe"), byMethod.entrySet().stream().map(e -> new Point(e.getKey(),
                        List.of(refundsOnly ? e.getValue().negate() : e.getValue()))).toList());
        Chart daily = dailyChart(c, "perDay", refundsOnly ? "Devoluciones por día" : "Neto por día", "money", List.of("Importe"),
                rows, r -> List.of(refundsOnly ? ((BigDecimal) r.get("amount")).negate() : (BigDecimal) r.get("amount")),
                r -> (LocalDate) r.get("_date"));
        return c.build("Por fecha en que se completó el movimiento", metrics, List.of(methodChart, daily),
                List.of(col("at", "Fecha", "datetime"), col("folio", "Folio"), col("guest", "Titular"), col("kind", "Tipo"),
                        col("method", "Método"), col("status", "Estado"), col("amount", "Importe", "money"),
                        col("reference", "Referencia"), col("user", "Registró")),
                rows, List.of("Los reembolsos y reversos aparecen con signo negativo."));
    }

    private Report receivables(Ctx c) {
        Sql q = new Sql("""
                SELECT r.folio, r.contact_name, r.contact_email, r.status, r.check_in, r.check_out,
                       %s AS due, r.paid_total
                FROM reservations r WHERE r.hotel_id = ? AND r.status IN %s AND %s <> r.paid_total"""
                .formatted(AMOUNT_DUE_SQL, COMMITTED, AMOUNT_DUE_SQL), c.hotelId);
        q.andIf(c.f.status(), "r.status = ?");
        q.add(" ORDER BY (" + AMOUNT_DUE_SQL + " - r.paid_total) DESC, r.check_in");
        LocalDate today = LocalDate.now(clock.withZone(c.zone));
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            BigDecimal due = rs.getBigDecimal("due");
            BigDecimal paid = rs.getBigDecimal("paid_total");
            BigDecimal balance = due.subtract(paid);
            String st = rs.getString("status");
            LocalDate checkOut = rs.getDate("check_out").toLocalDate();
            boolean overdue = balance.signum() > 0 && (Set.of("CHECKED_OUT", "CANCELLED", "NO_SHOW").contains(st) || checkOut.isBefore(today));
            return row("folio", rs.getString("folio"), "guest", rs.getString("contact_name"),
                    "email", c.customers ? rs.getString("contact_email") : null,
                    "status", RES_STATUS.get(st), "checkIn", rs.getDate("check_in").toLocalDate().toString(),
                    "checkOut", checkOut.toString(), "due", due, "paid", paid, "balance", balance,
                    "situation", balance.signum() < 0 ? "Saldo a favor" : overdue ? "Vencido" : "Por cobrar");
        });
        BigDecimal receivable = sumWhere(rows, "balance", r -> ((BigDecimal) r.get("balance")).signum() > 0);
        BigDecimal credit = sumWhere(rows, "balance", r -> ((BigDecimal) r.get("balance")).signum() < 0).negate();
        BigDecimal overdue = sumWhere(rows, "balance", r -> "Vencido".equals(r.get("situation")));
        List<Metric> metrics = List.of(
                metric("receivable", "Por cobrar", receivable, "money", "Lo adeudado menos lo pagado, en cuentas comprometidas"),
                metric("overdue", "Vencido", overdue, "money", "Huéspedes que ya salieron o reservaciones canceladas con saldo"),
                metric("accounts", "Cuentas con saldo", rows.stream().filter(r -> ((BigDecimal) r.get("balance")).signum() > 0).count(), "count", null),
                metric("credit", "Saldos a favor", credit, "money", "Pagos por encima de lo adeudado: posible reembolso"));
        List<Column> cols = new ArrayList<>(List.of(col("folio", "Folio"), col("guest", "Titular")));
        if (c.customers) {
            cols.add(col("email", "Correo"));
        }
        cols.addAll(List.of(col("status", "Estado"), col("checkIn", "Llegada", "date"), col("checkOut", "Salida", "date"),
                col("due", "Adeudado", "money"), col("paid", "Pagado", "money"), col("balance", "Saldo", "money"),
                col("situation", "Situación")));
        return c.build("Situación actual (no depende del periodo)", metrics, List.of(), cols, rows,
                List.of("Las reservaciones pendientes de confirmar no se incluyen: su importe aún no es una deuda."));
    }

    private Report cash(Ctx c) {
        Sql q = new Sql("""
                SELECT m.created_at, m.session_id, nullif(trim(u.first_name || ' ' || u.last_name), '') AS user_name,
                       m.type, m.method, m.direction, m.amount, r.folio, m.reason
                FROM cash_movements m JOIN users u ON u.id = m.user_id
                LEFT JOIN payments p ON p.id = m.payment_id LEFT JOIN reservations r ON r.id = p.reservation_id
                WHERE m.hotel_id = ? AND m.created_at >= ? AND m.created_at < ?""", c.hotelId, c.start(), c.end());
        q.andIf(c.f.method(), "m.method = ?");
        q.add(" ORDER BY m.created_at, m.id");
        List<Map<String, Object>> rows = q.query(jdbc, rs -> {
            boolean in = "IN".equals(rs.getString("direction"));
            BigDecimal amount = rs.getBigDecimal("amount");
            return row("at", c.dateTime(rs, "created_at"), "session", "#" + rs.getLong("session_id"),
                    "user", rs.getString("user_name"), "type", CASH_TYPE.get(rs.getString("type")),
                    "method", METHOD.get(rs.getString("method")), "_method", rs.getString("method"),
                    "direction", in ? "Ingreso" : "Egreso", "amount", in ? amount : amount.negate(),
                    "folio", rs.getString("folio"), "reason", rs.getString("reason"));
        });
        Map<String, Object> sessions = jdbc.queryForMap("""
                SELECT count(*) AS closed, coalesce(sum(difference), 0) AS diff FROM cash_sessions
                WHERE hotel_id = ? AND status = 'CLOSED' AND closed_at >= ? AND closed_at < ?""", c.hotelId, c.start(), c.end());
        BigDecimal cashIn = sumWhere(rows, r -> "CASH".equals(r.get("_method")) && ((BigDecimal) r.get("amount")).signum() > 0);
        BigDecimal cashOut = sumWhere(rows, r -> "CASH".equals(r.get("_method")) && ((BigDecimal) r.get("amount")).signum() < 0).negate();
        List<Metric> metrics = List.of(
                metric("cashIn", "Entradas de efectivo", cashIn, "money", "Cobros y depósitos en efectivo"),
                metric("cashOut", "Salidas de efectivo", cashOut, "money", "Reembolsos, reversos y retiros en efectivo"),
                metric("card", "Tarjeta (terminal)", sumWhere(rows, r -> "CARD".equals(r.get("_method"))), "money", "Neto cobrado en terminal"),
                metric("transfer", "Transferencias", sumWhere(rows, r -> "TRANSFER".equals(r.get("_method"))), "money", "Neto registrado en caja"),
                metric("sessions", "Cortes cerrados", ((Number) sessions.get("closed")).longValue(), "count", "Cajas cerradas en el periodo"),
                metric("difference", "Diferencias en cortes", money((BigDecimal) sessions.get("diff")), "money",
                        "Contado menos esperado. Negativo = faltante"));
        Map<String, BigDecimal[]> byMethod = new LinkedHashMap<>();
        for (String m : List.of("CASH", "CARD", "TRANSFER")) {
            byMethod.put(METHOD.get(m), zeros(2));
        }
        rows.forEach(r -> {
            BigDecimal a = (BigDecimal) r.get("amount");
            BigDecimal[] v = byMethod.get((String) r.get("method"));
            if (a.signum() > 0) {
                v[0] = v[0].add(a);
            } else {
                v[1] = v[1].add(a.negate());
            }
        });
        Chart chart = new Chart("byMethod", "Entradas y salidas por método", "bar", "money", List.of("Entradas", "Salidas"),
                byMethod.entrySet().stream().map(e -> new Point(e.getKey(), List.of(e.getValue()[0], e.getValue()[1]))).toList());
        return c.build("Por fecha del movimiento", metrics, List.of(chart),
                List.of(col("at", "Fecha", "datetime"), col("session", "Caja"), col("user", "Usuario"), col("type", "Tipo"),
                        col("method", "Método"), col("direction", "Sentido"), col("amount", "Importe", "money"),
                        col("folio", "Folio"), col("reason", "Motivo")),
                rows, List.of("Los pagos en línea no pasan por caja: consúltalos en Pagos por método."));
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        long hotelId = TenantContext.requireHotelId();
        Hotel hotel = hotelDirectory.current();
        ZoneId zone = hotel.zoneId();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        OffsetDateTime dayStart = startOf(today, zone);
        OffsetDateTime dayEnd = startOf(today.plusDays(1), zone);
        OffsetDateTime monthStart = startOf(today.withDayOfMonth(1), zone);

        Rooms rooms = jdbc.queryForObject("""
                SELECT count(*) AS total,
                  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM room_occupancies o JOIN reservations r ON r.id = o.reservation_id
                      WHERE o.room_id = rm.id AND o.kind = 'RESERVATION' AND r.status IN ('CONFIRMED','IN_HOUSE') AND o.stay @> ?::date)) AS occupied,
                  count(*) FILTER (WHERE rm.operational_status = 'MAINTENANCE' OR EXISTS (SELECT 1 FROM room_occupancies o
                      WHERE o.room_id = rm.id AND o.kind = 'BLOCK' AND o.stay @> ?::date)) AS maintenance,
                  count(*) FILTER (WHERE rm.operational_status = 'OUT_OF_SERVICE') AS oos
                FROM rooms rm WHERE rm.hotel_id = ? AND rm.active""", (rs, i) -> {
            long total = rs.getLong("total");
            long occupied = rs.getLong("occupied");
            long oos = rs.getLong("oos");
            long maintenance = rs.getLong("maintenance");
            long sellable = Math.max(0, total - oos - maintenance);
            return new Rooms(total, Math.max(0, sellable - occupied), occupied, maintenance, oos, pct(occupied, sellable));
        }, today, today, hotelId);

        Today t = jdbc.queryForObject("""
                SELECT
                  count(*) FILTER (WHERE created_at >= ? AND created_at < ?) AS created,
                  count(*) FILTER (WHERE check_in = ? AND status IN ('PENDING','PENDING_PAYMENT','CONFIRMED','IN_HOUSE','CHECKED_OUT')) AS arrivals,
                  count(*) FILTER (WHERE check_out = ? AND status IN ('CONFIRMED','IN_HOUSE','CHECKED_OUT')) AS departures,
                  count(*) FILTER (WHERE status = 'IN_HOUSE') AS in_house,
                  count(*) FILTER (WHERE (status = 'CANCELLED' AND cancelled_at >= ? AND cancelled_at < ?)
                                      OR (status = 'NO_SHOW' AND no_show_at >= ? AND no_show_at < ?)) AS cancellations,
                  count(*) FILTER (WHERE status IN ('PENDING','PENDING_PAYMENT')) AS pending
                FROM reservations WHERE hotel_id = ?""", (rs, i) -> new Today(rs.getLong("created"), rs.getLong("arrivals"),
                rs.getLong("departures"), rs.getLong("in_house"), rs.getLong("cancellations"), rs.getLong("pending")),
                dayStart, dayEnd, today, today, dayStart, dayEnd, dayStart, dayEnd, hotelId);

        Function<String, List<Point>> grouped = (column) -> jdbc.query(
                "SELECT " + column + ", count(*) FROM reservations WHERE hotel_id = ? AND created_at >= ? GROUP BY 1 ORDER BY 2 DESC",
                (rs, i) -> new Point(rs.getString(1), List.of(rs.getLong(2))), hotelId, monthStart);
        List<Point> byChannel = grouped.apply("source").stream()
                .map(p -> new Point(SOURCE.getOrDefault(p.label(), p.label()), p.values())).toList();
        List<Point> byStatus = grouped.apply("status").stream()
                .map(p -> new Point(RES_STATUS.getOrDefault(p.label(), p.label()), p.values())).toList();

        List<Point> next14 = jdbc.query("""
                SELECT d::date,
                  (SELECT count(*) FROM rooms rm WHERE rm.hotel_id = ? AND rm.active AND rm.operational_status <> 'OUT_OF_SERVICE')
                  - (SELECT count(DISTINCT o.room_id) FROM room_occupancies o JOIN rooms rm ON rm.id = o.room_id
                      WHERE o.hotel_id = ? AND o.kind = 'BLOCK' AND rm.active AND o.stay @> d::date),
                  (SELECT count(DISTINCT o.room_id) FROM room_occupancies o JOIN reservations r ON r.id = o.reservation_id
                      WHERE o.hotel_id = ? AND o.kind = 'RESERVATION' AND r.status IN ('CONFIRMED','IN_HOUSE','CHECKED_OUT') AND o.stay @> d::date)
                FROM generate_series(?::date, ?::date, interval '1 day') d ORDER BY 1""",
                (rs, i) -> new Point(rs.getDate(1).toLocalDate().toString(), List.of(pct(rs.getLong(3), Math.max(0, rs.getLong(2))))),
                hotelId, hotelId, hotelId, today, today.plusDays(13));

        Finance finance = null;
        List<Point> income = null;
        if (can(FINANCIAL)) {
            Map<String, Object> f = jdbc.queryForMap("""
                    SELECT
                      coalesce(sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END)
                               FILTER (WHERE p.completed_at >= ? AND p.completed_at < ?), 0) AS today,
                      coalesce(sum(CASE WHEN p.kind = 'PAYMENT' THEN p.amount ELSE -p.amount END)
                               FILTER (WHERE p.completed_at >= ?), 0) AS month,
                      coalesce(sum(p.amount) FILTER (WHERE p.kind = 'PAYMENT' AND p.completed_at >= ?
                               AND (p.completed_at AT TIME ZONE ?)::date < r.check_in), 0) AS deposits
                    FROM payments p JOIN reservations r ON r.id = p.reservation_id
                    WHERE p.hotel_id = ? AND p.status = 'COMPLETED' AND p.completed_at >= ?""",
                    dayStart, dayEnd, monthStart, monthStart, zone.getId(), hotelId, monthStart);
            BigDecimal[] recv = receivableTotals(hotelId);
            Long pendingOnline = jdbc.queryForObject("""
                    SELECT count(*) FROM payments WHERE hotel_id = ? AND status = 'PENDING' AND method = 'ONLINE'
                    AND (expires_at IS NULL OR expires_at > now())""", Long.class, hotelId);
            finance = new Finance(money((BigDecimal) f.get("today")), money((BigDecimal) f.get("month")),
                    money((BigDecimal) f.get("deposits")), recv[0], recv[1].longValue(), pendingOnline == null ? 0 : pendingOnline,
                    hotel.getCurrency());
            Map<LocalDate, BigDecimal> daily = new TreeMap<>();
            LocalDate first = today.minusDays(29);
            for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
                daily.put(d, BigDecimal.ZERO);
            }
            jdbc.query("""
                    SELECT kind, amount, completed_at FROM payments
                    WHERE hotel_id = ? AND status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?""", (RowCallbackHandler) rs -> {
                LocalDate d = rs.getObject("completed_at", OffsetDateTime.class).atZoneSameInstant(zone).toLocalDate();
                BigDecimal a = "PAYMENT".equals(rs.getString("kind")) ? rs.getBigDecimal("amount") : rs.getBigDecimal("amount").negate();
                daily.computeIfPresent(d, (k, v) -> v.add(a));
            }, hotelId, startOf(first, zone), dayEnd);
            income = daily.entrySet().stream().map(e -> new Point(e.getKey().toString(), List.of(money(e.getValue())))).toList();
        }
        return new Dashboard(rooms, t, finance, byChannel, byStatus, next14, income);
    }

    /** [por cobrar, número de cuentas] sobre cuentas comprometidas con saldo positivo. */
    private BigDecimal[] receivableTotals(long hotelId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(%1$s - r.paid_total), 0), count(*) FROM reservations r
                WHERE r.hotel_id = ? AND r.status IN %2$s AND %1$s > r.paid_total""".formatted(AMOUNT_DUE_SQL, COMMITTED),
                (rs, i) -> new BigDecimal[]{money(rs.getBigDecimal(1)), BigDecimal.valueOf(rs.getLong(2))}, hotelId);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────────────────

    private void roomTypeFilter(Sql q, Ctx c, String reservationIdColumn) {
        if (c.f.roomTypeId() != null) {
            q.and("EXISTS (SELECT 1 FROM reservation_rooms rr WHERE rr.reservation_id = " + reservationIdColumn
                    + " AND rr.room_type_id = ?)", c.f.roomTypeId());
        }
    }

    private static boolean isLive(String status) {
        return Set.of("CONFIRMED", "IN_HOUSE", "CHECKED_OUT").contains(status);
    }

    /** Serie diaria que cubre todo el periodo (los días sin datos aparecen en cero). */
    private static Chart dailyChart(Ctx c, String key, String title, String format, List<String> series,
                                    List<Map<String, Object>> rows, Function<Map<String, Object>, List<? extends Number>> values,
                                    Function<Map<String, Object>, LocalDate> date) {
        TreeMap<LocalDate, BigDecimal[]> days = new TreeMap<>();
        for (LocalDate d = c.f.from(); !d.isAfter(c.f.to()); d = d.plusDays(1)) {
            days.put(d, zeros(series.size()));
        }
        for (Map<String, Object> r : rows) {
            BigDecimal[] v = days.get(date.apply(r));
            if (v == null) {
                continue;
            }
            List<? extends Number> add = values.apply(r);
            for (int i = 0; i < v.length; i++) {
                v[i] = v[i].add(new BigDecimal(add.get(i).toString()));
            }
        }
        return new Chart(key, title, "bar", format, series, days.entrySet().stream()
                .map(e -> new Point(e.getKey().toString(), Arrays.stream(e.getValue()).map(b -> (Number) b).toList())).toList());
    }

    private static Chart countChart(String key, String title, List<Map<String, Object>> rows, String field,
                                    java.util.Collection<String> order) {
        Map<String, Long> counts = rows.stream().collect(Collectors.groupingBy(r -> String.valueOf(r.get(field)), Collectors.counting()));
        List<Point> points = order.stream().filter(counts::containsKey).map(l -> new Point(l, List.of(counts.get(l)))).toList();
        return new Chart(key, title, "bar", "count", List.of("Reservaciones"), points);
    }

    private static Metric metric(String key, String label, Object value, String format, String hint) {
        return new Metric(key, label, value, format, hint);
    }

    private static Column col(String key, String label) {
        return new Column(key, label, "text");
    }

    private static Column col(String key, String label, String format) {
        return new Column(key, label, format);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return sumWhere(rows, key, r -> true);
    }

    private static BigDecimal sumWhere(List<Map<String, Object>> rows, java.util.function.Predicate<Map<String, Object>> p) {
        return sumWhere(rows, "amount", p);
    }

    private static BigDecimal sumWhere(List<Map<String, Object>> rows, String key, java.util.function.Predicate<Map<String, Object>> p) {
        return money(rows.stream().filter(p).map(r -> (BigDecimal) r.get(key)).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double pct(long part, long whole) {
        return whole <= 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static OffsetDateTime startOf(LocalDate d, ZoneId zone) {
        return d.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** Contexto de una ejecución: hotel, zona, filtros ya validados y alcance de permisos. */
    private record Ctx(long hotelId, ZoneId zone, String currency, Filters f, boolean financial, boolean customers,
                       int rowLimit, ReportInfo info) {

        OffsetDateTime start() {
            return startOf(f.from(), zone);
        }

        OffsetDateTime end() {
            return startOf(f.to().plusDays(1), zone);
        }

        long days() {
            return ChronoUnit.DAYS.between(f.from(), f.to()) + 1;
        }

        String dateTime(ResultSet rs, String column) throws SQLException {
            OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
            return v == null ? null : dateTime(v);
        }

        String dateTime(OffsetDateTime v) {
            return v.atZoneSameInstant(zone).format(DATETIME);
        }

        LocalDate date(ResultSet rs, String column) throws SQLException {
            return rs.getObject(column, OffsetDateTime.class).atZoneSameInstant(zone).toLocalDate();
        }

        /** Arma el reporte: quita columnas internas (prefijo _) y recorta filas al límite. */
        Report build(String basis, List<Metric> metrics, List<Chart> charts, List<Column> columns,
                     List<Map<String, Object>> rows, List<String> notes) {
            List<Map<String, Object>> visible = rows.stream().limit(rowLimit).map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                columns.forEach(col -> m.put(col.key(), r.get(col.key())));
                return m;
            }).toList();
            List<String> allNotes = new ArrayList<>(notes);
            if (rows.size() >= FETCH_LIMIT) {
                allNotes.add("El periodo tiene demasiados registros: acórtalo para ver el detalle completo.");
            }
            return new Report(info.type(), info.title(), basis, f.from(), f.to(), currency, Instant.now(), metrics,
                    charts, columns, visible, rows.size(), rows.size() > rowLimit, allNotes);
        }
    }

    @FunctionalInterface
    private interface RowReader {
        Map<String, Object> read(ResultSet rs) throws SQLException;
    }

    /** SQL armado con condiciones opcionales; los valores siempre van como parámetros. */
    private static final class Sql {
        private final StringBuilder sql;
        private final List<Object> args = new ArrayList<>();

        Sql(String base, Object... args) {
            this.sql = new StringBuilder(base);
            this.args.addAll(Arrays.asList(args));
        }

        Sql and(String condition, Object... values) {
            sql.append(" AND ").append(condition);
            args.addAll(Arrays.asList(values));
            return this;
        }

        Sql andIf(Object value, String condition) {
            return value == null ? this : and(condition, value);
        }

        Sql add(String fragment) {
            sql.append(fragment);
            return this;
        }

        String sql() {
            return sql.toString();
        }

        Object[] args() {
            return args.toArray();
        }

        List<Map<String, Object>> query(JdbcTemplate jdbc, RowReader reader) {
            String limited = sql + " LIMIT " + FETCH_LIMIT;
            return jdbc.query(limited, (rs, i) -> reader.read(rs), args());
        }
    }
}
