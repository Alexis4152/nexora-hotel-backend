package com.nexora.hotel.dto.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Forma común de todos los reportes: indicadores, gráficas y una tabla. La misma estructura alimenta la pantalla y
 * las exportaciones (CSV, Excel y PDF), así lo que se exporta es exactamente lo que se ve.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /** Formatos de celda/indicador: text, money, count, number, percent, date, datetime. */
    public record Column(String key, String label, String format) {
    }

    /** {@code hint} explica qué representa la cifra (cobrado, devengado, anticipo, reservado…). */
    public record Metric(String key, String label, Object value, String format, String hint) {
    }

    public record Point(String label, List<Number> values) {
    }

    /** kind: bar | line. {@code series} nombra cada valor de los puntos. */
    public record Chart(String key, String title, String kind, String format, List<String> series, List<Point> points) {
    }

    public record Report(String type, String title, String basis, LocalDate from, LocalDate to, String currency,
                         Instant generatedAt, List<Metric> metrics, List<Chart> charts, List<Column> columns,
                         List<Map<String, Object>> rows, int totalRows, boolean truncated, List<String> notes) {
    }

    /** Filtros aceptados; cada reporte usa los que le aplican e ignora el resto. */
    public record Filters(LocalDate from, LocalDate to, String status, String source, Long roomTypeId, String method,
                          String kind, String basis) {
    }

    /** Catálogo de reportes visible para el usuario actual (según sus permisos). */
    public record ReportInfo(String type, String title, String description, String group, boolean financial,
                             boolean snapshot, List<String> filters) {
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    /** Totales y series del dashboard. {@code finance} es nulo si el usuario no tiene REPORTS:FINANCIAL. */
    public record Dashboard(Rooms rooms, Today today, Finance finance, List<Point> byChannel, List<Point> byStatus,
                            List<Point> occupancyNext14, List<Point> incomeLast30) {
    }

    public record Rooms(long total, long available, long occupied, long maintenance, long outOfService,
                        double occupancyPercent) {
    }

    public record Today(long createdToday, long arrivals, long departures, long inHouse, long cancellations,
                        long pending) {
    }

    public record Finance(java.math.BigDecimal collectedToday, java.math.BigDecimal collectedMonth,
                          java.math.BigDecimal depositsMonth, java.math.BigDecimal receivable, long receivableCount,
                          long pendingOnline, String currency) {
    }
}
