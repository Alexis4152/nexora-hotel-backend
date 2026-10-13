package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Apoyo para pruebas de cobros y operación: hotel con habitaciones, reservaciones y llamadas con encabezados libres. */
abstract class OperationsTestSupport extends SecurityTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    /** Hoy en la zona de los hoteles de prueba. */
    protected static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Mexico_City"));
    }

    /** Hotel con un tipo "DBL" de {@code rooms} habitaciones a $1,000 la noche, sin impuestos. */
    protected record Setup(TestHotel hotel, String token, long typeId, List<Long> roomIds) {
        String slug() {
            return hotel.slug();
        }
    }

    protected Setup setup(int rooms) {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        Map<String, Object> type = new HashMap<>(Map.of("code", "DBL", "name", "Doble", "basePrice", "1000.00", "maxAdults", 3,
                "maxChildren", 1, "maxOccupancy", 3, "includedOccupancy", 2, "extraAdultFee", "300.00", "extraChildFee", "0"));
        type.putAll(Map.of("bedCount", 2, "bedType", "MATRIMONIAL", "sortOrder", 0));
        long typeId = call(HttpMethod.POST, "/api/admin/room-types", type, token, hotel.slug(), null).data().path("id").asLong();
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= rooms; i++) {
            ids.add(call(HttpMethod.POST, "/api/admin/rooms", Map.of("roomTypeId", typeId, "number", "10" + i, "sortOrder", i),
                    token, hotel.slug(), null).data().path("id").asLong());
        }
        jdbc.update("UPDATE tax_rates SET active = false WHERE hotel_id = ?", hotel.id());
        return new Setup(hotel, token, typeId, ids);
    }

    protected static Map<String, Object> guest(String email) {
        return Map.of("firstName", "María", "lastName", "Núñez", "email", email, "phoneCountryCode", "52", "phone", "9981234567");
    }

    /** Reservación de recepción; devuelve el detalle. */
    protected JsonNode booking(Setup s, LocalDate in, LocalDate out, boolean confirm) {
        Map<String, Object> body = new HashMap<>(Map.of("guest", guest("g" + System.nanoTime() + "@mail.mx"),
                "roomTypeId", s.typeId(), "checkIn", in.toString(), "checkOut", out.toString(), "adults", 2,
                "children", 0, "source", "FRONT_DESK", "confirmNow", confirm));
        Res res = call(HttpMethod.POST, "/api/admin/reservations", body, s.token(), s.slug(), null);
        if (res.status() != 201) {
            throw new IllegalStateException(res.body().toString());
        }
        return res.data();
    }

    protected static long id(JsonNode detail) {
        return detail.path("summary").path("id").asLong();
    }

    protected Res post(Setup s, String token, String path, Object body) {
        return call(HttpMethod.POST, path, body, token, s.slug(), null);
    }

    protected Res pay(Setup s, String token, long reservationId, String method, String amount, String reference, String key) {
        Map<String, Object> body = new HashMap<>(Map.of("method", method, "amount", amount));
        if (reference != null) {
            body.put("reference", reference);
        }
        Map<String, String> headers = new HashMap<>(Map.of("Authorization", "Bearer " + token, "X-Hotel", s.slug()));
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        return raw(HttpMethod.POST, "/api/admin/reservations/" + reservationId + "/payments", body, headers);
    }

    protected Res openCash(Setup s, String token, String amount) {
        return post(s, token, "/api/admin/cash/open", Map.of("openingFloat", amount));
    }

    protected Res raw(HttpMethod method, String path, Object body, Map<String, String> headerValues) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headerValues.forEach(headers::set);
        var response = restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
        try {
            String text = response.getBody();
            return new Res(response.getStatusCode().value(), text == null || text.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(text), response.getHeaders());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Usuario con un rol hecho a la medida. Devuelve su token. */
    protected String userWith(Setup s, String... permissions) {
        Res role = post(s, s.token(), "/api/admin/roles", Map.of("name", "Rol " + UUID.randomUUID().toString().substring(0, 6),
                "permissions", List.of(permissions)));
        if (role.status() != 201) {
            throw new IllegalStateException(role.body().toString());
        }
        return token(createUser(s.hotel(), role.data().path("code").asText()).getEmail(), s.slug());
    }

    protected String receptionist(Setup s) {
        return token(createUser(s.hotel(), "RECEPTIONIST").getEmail(), s.slug());
    }
}
