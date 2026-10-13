package com.nexora.hotel.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nexora.hotel.dto.catalog.ReportDtos.Column;
import com.nexora.hotel.dto.catalog.ReportDtos.ExportFile;
import com.nexora.hotel.dto.catalog.ReportDtos.Metric;
import com.nexora.hotel.dto.catalog.ReportDtos.Report;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.i18n.Translator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exporta un {@link Report} tal como se ve en pantalla. CSV (UTF-8 con BOM para que Excel respete acentos), Excel
 * (.xlsx con Apache POI en modo streaming) y PDF (OpenPDF, horizontal). Las celdas de texto que empiezan con
 * {@code = + - @} se neutralizan para que una hoja de cálculo nunca las ejecute como fórmula.
 */
@Service
public class ReportExporter {

    private final Translator translator;

    public ReportExporter(Translator translator) {
        this.translator = translator;
    }

    /** Formato de día según el idioma de la petición. */
    private static DateTimeFormatter day() {
        return DateTimeFormatter.ofPattern(Translator.english() ? "MM/dd/yyyy" : "dd/MM/yyyy");
    }

    private static String tr(String text, Object... args) {
        return Translator.tr(text, args);
    }
    private static final Color INK = new Color(10, 15, 28);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color HEAD = new Color(241, 245, 249);
    private static final Color LINE = new Color(226, 232, 240);

    public ExportFile export(Report original, String format, String hotelName, String hotelSlug, ZoneId zone) {
        Report report = Translator.english() ? translate(original) : original;
        String base = "%s-%s-%s_%s".formatted(hotelSlug, report.type(), report.from(), report.to());
        try {
            return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
                case "csv" -> new ExportFile(base + ".csv", "text/csv; charset=UTF-8", csv(report));
                case "xlsx" -> new ExportFile(base + ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(report, hotelName));
                case "pdf" -> new ExportFile(base + ".pdf", "application/pdf", pdf(report, hotelName, zone));
                default -> throw new BusinessException(tr("Formato no válido. Usa csv, xlsx o pdf"));
            };
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el archivo", e);
        }
    }

    /** Copia del reporte en inglés: textos del servidor con plantillas; celdas solo por coincidencia exacta. */
    private Report translate(Report r) {
        var metrics = r.metrics().stream().map(m -> new com.nexora.hotel.dto.catalog.ReportDtos.Metric(m.key(),
                translator.toEnglish(m.label(), true), m.value() instanceof String v ? translator.toEnglish(v, true) : m.value(),
                m.format(), translator.toEnglish(m.hint(), true))).toList();
        var columns = r.columns().stream().map(c -> new Column(c.key(), translator.toEnglish(c.label(), true), c.format())).toList();
        var rows = r.rows().stream().map(row -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            row.forEach((k, v) -> copy.put(k, v instanceof String sv ? translator.toEnglish(sv, false) : v));
            return copy;
        }).toList();
        var notes = r.notes().stream().map(n -> translator.toEnglish(n, true)).toList();
        return new Report(r.type(), translator.toEnglish(r.title(), true), translator.toEnglish(r.basis(), true), r.from(), r.to(),
                r.currency(), r.generatedAt(), metrics, r.charts(), columns, rows, r.totalRows(), r.truncated(), notes);
    }

    // ── CSV ──────────────────────────────────────────────────────────────────────────────

    byte[] csv(Report r) {
        StringBuilder sb = new StringBuilder("﻿");
        List<Column> cols = r.columns();
        sb.append(String.join(",", cols.stream().map(c -> csvCell(c.label())).toList())).append("\r\n");
        for (Map<String, Object> row : r.rows()) {
            sb.append(String.join(",", cols.stream().map(c -> csvValue(row.get(c.key()), c.format())).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvValue(Object v, String format) {
        if (v == null) {
            return "";
        }
        if (v instanceof Number n) {
            return "percent".equals(format) ? n.toString() : plain(n);
        }
        return csvCell(String.valueOf(v));
    }

    private static String csvCell(String s) {
        String safe = neutralize(s);
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /** Prefija con apóstrofo el texto que una hoja de cálculo interpretaría como fórmula (CSV/fórmula injection). */
    static String neutralize(String s) {
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }

    private static String plain(Number n) {
        return n instanceof BigDecimal b ? b.toPlainString() : n.toString();
    }

    // ── Excel ────────────────────────────────────────────────────────────────────────────

    byte[] xlsx(Report r, String hotelName) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            var boldFont = wb.createFont();
            boldFont.setBold(true);
            bold.setFont(boldFont);
            CellStyle header = wb.createCellStyle();
            header.cloneStyleFrom(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle moneyStyle = wb.createCellStyle();
            moneyStyle.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle pctStyle = wb.createCellStyle();
            pctStyle.setDataFormat(wb.createDataFormat().getFormat("0.0\"%\""));
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat(Translator.english() ? "mm/dd/yyyy" : "dd/mm/yyyy"));

            Sheet summary = wb.createSheet(tr("Resumen"));
            int i = 0;
            text(summary.createRow(i++), 0, hotelName, bold);
            text(summary.createRow(i++), 0, r.title(), bold);
            text(summary.createRow(i++), 0, tr("Periodo: {0} a {1} · {2}", r.from().format(day()), r.to().format(day()), r.basis()), null);
            text(summary.createRow(i++), 0, tr("Moneda: {0}", r.currency()), null);
            i++;
            Row mh = summary.createRow(i++);
            text(mh, 0, tr("Indicador"), header);
            text(mh, 1, tr("Valor"), header);
            text(mh, 2, tr("Qué representa"), header);
            for (Metric m : r.metrics()) {
                Row row = summary.createRow(i++);
                text(row, 0, m.label(), null);
                value(row.createCell(1), m.value(), m.format(), moneyStyle, pctStyle, dateStyle);
                text(row, 2, m.hint(), null);
            }
            for (String note : r.notes()) {
                text(summary.createRow(++i), 0, note, null);
            }
            summary.setColumnWidth(0, 32 * 256);
            summary.setColumnWidth(1, 18 * 256);
            summary.setColumnWidth(2, 70 * 256);

            Sheet data = wb.createSheet(tr("Detalle"));
            Row h = data.createRow(0);
            List<Column> cols = r.columns();
            for (int c = 0; c < cols.size(); c++) {
                text(h, c, cols.get(c).label(), header);
                data.setColumnWidth(c, Math.min(45, Math.max(12, cols.get(c).label().length() + 4)) * 256);
            }
            data.createFreezePane(0, 1);
            int n = 1;
            for (Map<String, Object> row : r.rows()) {
                Row x = data.createRow(n++);
                for (int c = 0; c < cols.size(); c++) {
                    value(x.createCell(c), row.get(cols.get(c).key()), cols.get(c).format(), moneyStyle, pctStyle, dateStyle);
                }
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        }
    }

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(neutralize(value));
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void value(Cell cell, Object v, String format, CellStyle money, CellStyle pct, CellStyle date) {
        if (v == null) {
            return;
        }
        if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            if ("money".equals(format)) {
                cell.setCellStyle(money);
            } else if ("percent".equals(format)) {
                cell.setCellStyle(pct);
            }
            return;
        }
        String s = String.valueOf(v);
        if ("date".equals(format) && s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            cell.setCellValue(LocalDate.parse(s));
            cell.setCellStyle(date);
            return;
        }
        cell.setCellValue(neutralize(s));
    }

    // ── PDF ──────────────────────────────────────────────────────────────────────────────

    byte[] pdf(Report r, String hotelName, ZoneId zone) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER.rotate(), 32, 32, 32, 32);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = new Font(Font.HELVETICA, 18, Font.BOLD, INK);
        Font h = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
        Font normal = new Font(Font.HELVETICA, 8, Font.NORMAL, INK);
        Font small = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
        Font big = new Font(Font.HELVETICA, 13, Font.BOLD, INK);
        NumberFormat money = NumberFormat.getCurrencyInstance(Translator.locale());
        try {
            money.setCurrency(Currency.getInstance(r.currency()));
        } catch (IllegalArgumentException ignored) {
            // moneda desconocida: se muestra con el símbolo local
        }

        doc.add(new Paragraph(hotelName, small));
        doc.add(new Paragraph(r.title(), title));
        doc.add(new Paragraph(tr("Periodo: {0} a {1} · {2} · Generado {3}", r.from().format(day()), r.to().format(day()), r.basis(),
                java.time.ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern(Translator.english() ? "MM/dd/yyyy h:mm a" : "dd/MM/yyyy HH:mm", Translator.locale()))), small));

        if (!r.metrics().isEmpty()) {
            int perRow = Math.min(4, r.metrics().size());
            PdfPTable kpis = new PdfPTable(perRow);
            kpis.setWidthPercentage(100);
            kpis.setSpacingBefore(12);
            for (Metric m : r.metrics()) {
                PdfPCell c = new PdfPCell();
                c.setBorderColor(LINE);
                c.setPadding(8);
                c.addElement(new Paragraph(m.label(), small));
                c.addElement(new Paragraph(display(m.value(), m.format(), money), big));
                if (m.hint() != null) {
                    c.addElement(new Paragraph(m.hint(), small));
                }
                kpis.addCell(c);
            }
            int pad = (perRow - r.metrics().size() % perRow) % perRow;
            for (int i = 0; i < pad; i++) {
                PdfPCell empty = new PdfPCell();
                empty.setBorder(PdfPCell.NO_BORDER);
                kpis.addCell(empty);
            }
            doc.add(kpis);
        }

        List<Column> cols = r.columns();
        PdfPTable table = new PdfPTable(cols.size());
        table.setWidthPercentage(100);
        table.setSpacingBefore(14);
        table.setHeaderRows(1);
        for (Column c : cols) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), h));
            cell.setBackgroundColor(HEAD);
            cell.setBorderColor(LINE);
            cell.setPadding(4);
            table.addCell(cell);
        }
        for (Map<String, Object> row : r.rows()) {
            for (Column c : cols) {
                PdfPCell cell = new PdfPCell(new Phrase(display(row.get(c.key()), c.format(), money), normal));
                cell.setBorderColor(LINE);
                cell.setPadding(4);
                if (!"text".equals(c.format()) && !"date".equals(c.format()) && !"datetime".equals(c.format())) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        if (r.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase(tr("Sin registros en el periodo"), small));
            empty.setColspan(cols.size());
            empty.setPadding(10);
            empty.setBorderColor(LINE);
            table.addCell(empty);
        }
        doc.add(table);
        if (r.truncated()) {
            doc.add(new Paragraph(tr("Se muestran {0} de {1} registros.", r.rows().size(), r.totalRows()), small));
        }
        for (String note : r.notes()) {
            doc.add(new Paragraph(note, small));
        }
        doc.close();
        return out.toByteArray();
    }

    private static String display(Object v, String format, NumberFormat money) {
        if (v == null) {
            return "—";
        }
        if (v instanceof Number n) {
            return switch (format) {
                case "money" -> money.format(n);
                case "percent" -> NumberFormat.getNumberInstance(Translator.locale()).format(n) + " %";
                default -> NumberFormat.getNumberInstance(Translator.locale()).format(n);
            };
        }
        String s = String.valueOf(v);
        if ("date".equals(format) && s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(s).format(day());
        }
        if ("datetime".equals(format) && s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
            return LocalDate.parse(s.substring(0, 10)).format(day()) + s.substring(10);
        }
        return s;
    }
}
