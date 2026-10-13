package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 9: dashboard, reportes, permisos por tipo de reporte y exportaciones. */
class ReportIT extends OperationsTestSupport {

    private static JsonNode metric(JsonNode report, String key) {
        for (JsonNode m : report.path("metrics")) {
            if (key.equals(m.path("key").asText())) {
                return m.path("value");
            }
        }
        return null;
    }

    private static JsonNode rowWith(JsonNode report, String field, String value) {
        for (JsonNode r : report.path("rows")) {
            if (value.equals(r.path(field).asText())) {
                return r;
            }
        }
        return null;
    }

    private ResponseEntity<byte[]> download(Setup s, String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Hotel", s.slug());
        return restTemplate.exchange(url(path), HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    /** Escenario: 2 habitaciones, una estancia hoy con abono en efectivo, un anticipo con tarjeta, una pendiente y una cancelada. */
    private record Scenario(Setup s, String stay, String advance, String cancelled) {
    }

    private Scenario scenario() {
        Setup s = setup(2);
        assertThat(openCash(s, s.token(), "0").status()).isEqualTo(201);
        JsonNode stay = booking(s, today(), today().plusDays(2), true);
        assertThat(pay(s, s.token(), id(stay), "CASH", "500.00", null, null).status()).isEqualTo(201);
        JsonNode advance = booking(s, today().plusDays(5), today().plusDays(6), true);
        assertThat(pay(s, s.token(), id(advance), "CARD", "300.00", "AUT-77", null).status()).isEqualTo(201);
        booking(s, today().plusDays(1), today().plusDays(2), false);
        JsonNode cancelled = booking(s, today().plusDays(3), today().plusDays(4), true);
        Res cancel = post(s, s.token(), "/api/admin/reservations/" + id(cancelled) + "/cancel",
                Map.of("reason", "=HYPERLINK(\"http://x\")", "waivePenalty", true));
        assertThat(cancel.status()).as(cancel.body().toString()).isEqualTo(200);
        return new Scenario(s, stay.path("summary").path("folio").asText(), advance.path("summary").path("folio").asText(),
                cancelled.path("summary").path("folio").asText());
    }

    @Test
    void financialReportsSeparateCollectedAccruedAndReceivable() {
        Scenario sc = scenario();
        Setup s = sc.s();
        String range = "?from=" + today() + "&to=" + today();

        JsonNode payments = get("/api/admin/reports/payments" + range, s.token(), s.slug()).data();
        assertThat(metric(payments, "in").decimalValue()).isEqualByComparingTo("800");
        assertThat(metric(payments, "net").decimalValue()).isEqualByComparingTo("800");
        assertThat(metric(payments, "count").asInt()).isEqualTo(2);

        JsonNode income = get("/api/admin/reports/income" + range, s.token(), s.slug()).data();
        assertThat(metric(income, "collected").decimalValue()).isEqualByComparingTo("800");
        assertThat(metric(income, "deposits").decimalValue()).as("solo el pago antes de la llegada").isEqualByComparingTo("300");
        assertThat(metric(income, "lodging").decimalValue()).as("una noche de la estancia de hoy").isEqualByComparingTo("1000");

        JsonNode recv = get("/api/admin/reports/receivables", s.token(), s.slug()).data();
        assertThat(rowWith(recv, "folio", sc.stay()).path("balance").decimalValue()).isEqualByComparingTo("1500");
        assertThat(rowWith(recv, "folio", sc.advance()).path("balance").decimalValue()).isEqualByComparingTo("700");
        assertThat(rowWith(recv, "folio", sc.cancelled())).as("cancelada sin penalización no debe nada").isNull();
        assertThat(metric(recv, "receivable").decimalValue()).isEqualByComparingTo("2200");

        JsonNode cash = get("/api/admin/reports/cash" + range, s.token(), s.slug()).data();
        assertThat(metric(cash, "cashIn").decimalValue()).isEqualByComparingTo("500");
        assertThat(metric(cash, "card").decimalValue()).isEqualByComparingTo("300");

        JsonNode dash = get("/api/admin/dashboard", s.token(), s.slug()).data();
        assertThat(dash.path("finance").path("collectedToday").decimalValue()).isEqualByComparingTo("800");
        assertThat(dash.path("finance").path("depositsMonth").decimalValue()).isEqualByComparingTo("300");
        assertThat(dash.path("rooms").path("total").asInt()).isEqualTo(2);
        assertThat(dash.path("rooms").path("occupied").asInt()).isEqualTo(1);
        assertThat(dash.path("rooms").path("occupancyPercent").asDouble()).isEqualTo(50.0);
        assertThat(dash.path("today").path("arrivals").asInt()).isEqualTo(1);
        assertThat(dash.path("today").path("cancellations").asInt()).isEqualTo(1);
        assertThat(dash.path("occupancyNext14")).hasSize(14);
        assertThat(dash.path("incomeLast30")).hasSize(30);
    }

    @Test
    void operationalReportsCountRealOccupancyAndCancellations() {
        Scenario sc = scenario();
        Setup s = sc.s();
        JsonNode occ = get("/api/admin/reports/occupancy?from=" + today() + "&to=" + today().plusDays(1), s.token(), s.slug()).data();
        assertThat(occ.path("rows")).hasSize(2);
        assertThat(occ.path("rows").get(0).path("sold").asInt()).isEqualTo(1);
        assertThat(occ.path("rows").get(1).path("sold").asInt()).as("la pendiente no cuenta como vendida").isEqualTo(1);
        assertThat(metric(occ, "occupancy").asDouble()).isEqualTo(50.0);

        JsonNode res = get("/api/admin/reports/reservations", s.token(), s.slug()).data();
        assertThat(metric(res, "count").asInt()).isEqualTo(4);
        assertThat(metric(res, "pending").asInt()).isEqualTo(1);
        assertThat(metric(res, "cancelled").asInt()).isEqualTo(1);

        JsonNode byArrival = get("/api/admin/reports/reservations?basis=arrival&from=" + today() + "&to=" + today(), s.token(), s.slug()).data();
        assertThat(metric(byArrival, "count").asInt()).isEqualTo(1);

        JsonNode cancels = get("/api/admin/reports/cancellations", s.token(), s.slug()).data();
        assertThat(metric(cancels, "cancelled").asInt()).isEqualTo(1);
        assertThat(metric(cancels, "rate").asDouble()).isEqualTo(25.0);

        JsonNode pending = get("/api/admin/reports/pending", s.token(), s.slug()).data();
        assertThat(metric(pending, "count").asInt()).isEqualTo(1);

        JsonNode rooms = get("/api/admin/reports/rooms?from=" + today() + "&to=" + today().plusDays(6), s.token(), s.slug()).data();
        assertThat(rooms.path("rows")).hasSize(2);
        assertThat(metric(rooms, "nights").asInt()).as("2 noches de la estancia + 1 del anticipo").isEqualTo(3);

        // Otro hotel no ve nada de éste.
        Setup other = setup(1);
        assertThat(metric(get("/api/admin/reports/reservations", other.token(), other.slug()).data(), "count").asInt()).isZero();
    }

    @Test
    void permissionsHideMoneyAndBlockFinancialReportsAndExports() {
        Scenario sc = scenario();
        Setup s = sc.s();
        String desk = receptionist(s);

        JsonNode catalog = get("/api/admin/reports", desk, s.slug()).data();
        assertThat(catalog.toString()).contains("\"occupancy\"").doesNotContain("\"payments\"").doesNotContain("\"income\"");
        JsonNode res = get("/api/admin/reports/reservations", desk, s.slug()).data();
        assertThat(res.path("columns").toString()).doesNotContain("\"total\"");
        assertThat(metric(res, "booked")).as("recepción no ve importes").isNull();
        assertThat(res.path("rows").get(0).has("total")).isFalse();

        assertThat(get("/api/admin/reports/payments", desk, s.slug()).status()).isEqualTo(403);
        assertThat(get("/api/admin/reports/receivables", desk, s.slug()).status()).isEqualTo(403);
        assertThat(download(s, desk, "/api/admin/reports/occupancy/export?format=csv").getStatusCode().value())
                .as("exportar requiere REPORTS:EXPORT").isEqualTo(403);
        assertThat(get("/api/admin/dashboard", desk, s.slug()).data().path("finance").isNull()
                || get("/api/admin/dashboard", desk, s.slug()).data().path("finance").isMissingNode()).as("sin cifras financieras").isTrue();

        String viewer = userWith(s, "REPORTS:OPERATIONAL", "REPORTS:EXPORT");
        assertThat(download(s, viewer, "/api/admin/reports/occupancy/export?format=csv").getStatusCode().value()).isEqualTo(200);
        assertThat(download(s, viewer, "/api/admin/reports/income/export?format=csv").getStatusCode().value())
                .as("exportar no salta el permiso del reporte").isEqualTo(403);
        assertThat(get("/api/admin/dashboard", viewer, s.slug()).status()).isEqualTo(403);
    }

    @Test
    void exportsInEveryFormatAreAuditedAndNeutralizeFormulas() {
        Scenario sc = scenario();
        Setup s = sc.s();

        ResponseEntity<byte[]> csv = download(s, s.token(), "/api/admin/reports/cancellations/export?format=csv");
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getHeaders().getContentDisposition().getFilename()).endsWith(".csv").startsWith(s.slug() + "-cancellations-");
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Folio,");
        assertThat(text).contains(sc.cancelled()).contains("'=HYPERLINK").doesNotContain(",=HYPERLINK");

        ResponseEntity<byte[]> xlsx = download(s, s.token(), "/api/admin/reports/payments/export?format=xlsx");
        assertThat(xlsx.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(xlsx.getBody(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");

        ResponseEntity<byte[]> pdf = download(s, s.token(), "/api/admin/reports/income/export?format=pdf");
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(pdf.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE hotel_id = ? AND action = 'REPORT_EXPORTED'",
                Integer.class, s.hotel().id())).isEqualTo(3);
        assertThat(download(s, s.token(), "/api/admin/reports/income/export?format=docx").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsInvalidRangesTypesAndFilters() {
        Setup s = setup(1);
        assertThat(get("/api/admin/reports/income?from=" + today() + "&to=" + today().minusDays(1), s.token(), s.slug()).status()).isEqualTo(400);
        assertThat(get("/api/admin/reports/income?from=" + today().minusDays(400) + "&to=" + today(), s.token(), s.slug()).status()).isEqualTo(400);
        assertThat(get("/api/admin/reports/nope", s.token(), s.slug()).status()).isEqualTo(404);
        assertThat(get("/api/admin/reports/reservations?status=1;DROP", s.token(), s.slug()).status()).isEqualTo(400);
        assertThat(get("/api/admin/reports/payments?method=BITCOIN", s.token(), s.slug()).status()).isEqualTo(400);
        JsonNode empty = get("/api/admin/reports/income", s.token(), s.slug()).data();
        assertThat(metric(empty, "collected").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.path("rows").size()).as("un renglón por día del mes en curso").isEqualTo(today().getDayOfMonth());
    }
}
