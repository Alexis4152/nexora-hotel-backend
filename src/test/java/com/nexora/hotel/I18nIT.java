package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ES | EN: el servidor responde en el idioma que pide la aplicación (Accept-Language); sin encabezado, en español. */
class I18nIT extends OperationsTestSupport {

    private Res as(String lang, HttpMethod method, String path, Object body, Setup s, String token) {
        Map<String, String> h = new HashMap<>(Map.of("X-Hotel", s.slug()));
        if (lang != null) {
            h.put("Accept-Language", lang);
        }
        if (token != null) {
            h.put("Authorization", "Bearer " + token);
        }
        return raw(method, path, body, h);
    }

    @Test
    void withoutLanguageHeaderEverythingStaysInSpanish() {
        Setup s = setup(1);
        Res login = as(null, HttpMethod.POST, "/api/auth/login", Map.of("email", s.hotel().adminEmail(), "password", "mala-1234567"), s, null);
        assertThat(login.body().path("message").asText()).startsWith("Correo o contraseña incorrectos");
        Res report = as("es-MX", HttpMethod.GET, "/api/admin/reports/income", null, s, s.token());
        assertThat(report.data().path("title").asText()).isEqualTo("Ingresos por periodo");
    }

    @Test
    void englishCoversErrorsValidationSessionAndSuccessMessages() {
        Setup s = setup(1);
        Res login = as("en", HttpMethod.POST, "/api/auth/login", Map.of("email", s.hotel().adminEmail(), "password", "mala-1234567"), s, null);
        assertThat(login.body().path("message").asText()).startsWith("Incorrect email or password");

        Res anonymous = as("en", HttpMethod.GET, "/api/admin/overview", null, s, null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("message").asText()).isEqualTo("Not authenticated");

        Res denied = as("en", HttpMethod.GET, "/api/admin/reports/payments", null, s, receptionist(s));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().path("message").asText()).isEqualTo("You don't have permission to perform this action");

        Res invalid = as("en", HttpMethod.POST, "/api/admin/reservations", Map.of(), s, s.token());
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().path("message").asText()).isEqualTo("Check the highlighted fields");
        assertThat(invalid.body().path("errors").toString()).contains("Enter the check-in date").doesNotContain("Indica");

        Res dates = as("en", HttpMethod.GET, "/api/admin/reservations/availability?checkIn=" + today().plusDays(3)
                + "&checkOut=" + today().plusDays(1), null, s, s.token());
        assertThat(dates.body().path("message").asText()).isEqualTo("Check-out must be after check-in");

        Map<String, Object> body = new HashMap<>(Map.of("guest", guest("en" + System.nanoTime() + "@mail.mx"),
                "roomTypeId", s.typeId(), "checkIn", today().toString(), "checkOut", today().plusDays(2).toString(),
                "adults", 2, "children", 0, "source", "FRONT_DESK", "confirmNow", true));
        Res created = as("en", HttpMethod.POST, "/api/admin/reservations", body, s, s.token());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("message").asText()).isEqualTo("Reservation created");
        long id = created.data().path("summary").path("id").asLong();

        // Texto armado por el servidor dentro de los datos, con valores: "Saldo pendiente de $2,000.00".
        JsonNode preview = as("en", HttpMethod.GET, "/api/admin/reservations/" + id + "/check-in-preview", null, s, s.token()).data();
        assertThat(preview.path("warnings").toString()).contains("Balance due of").doesNotContain("Saldo pendiente");
        // Desglose guardado en español al reservar: se muestra en el idioma pedido.
        JsonNode detail = as("en", HttpMethod.GET, "/api/admin/reservations/" + id, null, s, s.token()).data();
        assertThat(detail.path("priceLines").toString()).contains("\"Night\"").doesNotContain("\"Noche\"");
        // Lo que capturó el usuario no se toca.
        assertThat(detail.path("summary").path("guestName").asText()).isEqualTo("María Núñez");
    }

    @Test
    void reportsAndExportsInEnglish() {
        Setup s = setup(1);
        booking(s, today(), today().plusDays(1), true);
        JsonNode income = as("en", HttpMethod.GET, "/api/admin/reports/income", null, s, s.token()).data();
        assertThat(income.path("title").asText()).isEqualTo("Revenue by period");
        assertThat(income.path("metrics").get(0).path("label").asText()).isEqualTo("Net collected");
        assertThat(income.path("notes").toString()).contains("Collected and earned are different concepts");

        JsonNode res = as("en", HttpMethod.GET, "/api/admin/reports/reservations", null, s, s.token()).data();
        assertThat(res.path("rows").get(0).path("status").asText()).isEqualTo("Confirmed");
        assertThat(res.path("rows").get(0).path("source").asText()).isEqualTo("Front desk");

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(s.token());
        h.set("X-Hotel", s.slug());
        h.set("Accept-Language", "en");
        ResponseEntity<byte[]> csv = restTemplate.exchange(url("/api/admin/reports/reservations/export?format=csv"),
                HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Reservation no.,Created,Check-in,Check-out,Nights").contains("Confirmed");

        ResponseEntity<byte[]> pdf = restTemplate.exchange(url("/api/admin/reports/income/export?format=pdf"),
                HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(pdf.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
    }
}
