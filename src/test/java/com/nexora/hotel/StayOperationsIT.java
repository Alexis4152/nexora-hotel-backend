package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 7: check-in, check-out, no-show y limpieza. */
class StayOperationsIT extends OperationsTestSupport {

    private static Map<String, Object> checkIn(boolean idVerified, boolean acceptBalance) {
        return Map.of("identityVerified", idVerified, "acceptPendingBalance", acceptBalance,
                "guests", List.of(Map.of("fullName", "María Núñez", "minor", false, "idType", "INE", "idNumber", "NUMA800101")));
    }

    private String roomStatus(long roomId) {
        return jdbc.queryForObject("SELECT operational_status FROM rooms WHERE id = ?", String.class, roomId);
    }

    @Test
    void fullStayFromArrivalToDeparture() {
        Setup s = setup(1);
        long id = id(booking(s, today(), today().plusDays(2), true));
        String desk = receptionist(s);
        String base = "/api/admin/reservations/" + id;

        JsonNode preview = get(base + "/check-in-preview", desk, s.slug()).data();
        assertThat(preview.path("allowed").asBoolean()).isTrue();
        assertThat(preview.path("warnings").toString()).contains("Saldo pendiente");

        assertThat(post(s, desk, base + "/check-in", checkIn(false, true)).body().path("message").asText()).contains("identificación");
        assertThat(post(s, desk, base + "/check-in", checkIn(true, false)).body().path("message").asText()).contains("saldo pendiente");
        Res in = post(s, desk, base + "/check-in", checkIn(true, true));
        assertThat(in.status()).as(in.body().toString()).isEqualTo(200);
        assertThat(in.data().path("summary").path("status").asText()).isEqualTo("IN_HOUSE");
        assertThat(in.data().path("account").path("guests").get(0).path("idNumber").asText())
                .as("recepción no ve la identificación completa").isEqualTo("•••• 0101");
        assertThat(jdbc.queryForObject("SELECT id_number_enc FROM reservation_guests WHERE reservation_id = ?", String.class, id))
                .as("cifrada en la base").doesNotContain("NUMA800101");
        assertThat(roomStatus(s.roomIds().get(0))).isEqualTo("OCCUPIED");
        assertThat(post(s, desk, base + "/check-in", checkIn(true, true)).status()).as("check-in duplicado").isEqualTo(400);

        // Salida con saldo: recepción no puede autorizarla.
        assertThat(post(s, desk, base + "/check-out", Map.of()).body().path("message").asText()).contains("Saldo pendiente");
        assertThat(post(s, desk, base + "/check-out", Map.of("allowPendingBalance", true, "reason", "Paga mañana")).status()).isEqualTo(403);
        openCash(s, desk, "0");
        assertThat(post(s, desk, base + "/charges", Map.of("description", "Lavandería", "quantity", "1", "unitPrice", "150")).status()).isEqualTo(201);
        assertThat(pay(s, desk, id, "CASH", "2150.00", null, null).status()).isEqualTo(201);

        Res out = post(s, desk, base + "/check-out", Map.of("notes", "Todo en orden"));
        assertThat(out.status()).as(out.body().toString()).isEqualTo(200);
        assertThat(out.data().path("summary").path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(out.data().path("account").path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(roomStatus(s.roomIds().get(0))).as("pasa a limpieza").isEqualTo("CLEANING");
        // Salida anticipada (2 noches reservadas, sale el primer día): la noche siguiente queda libre.
        assertThat(jdbc.queryForObject("SELECT upper(stay) FROM room_occupancies WHERE reservation_id = ?", LocalDate.class, id))
                .isEqualTo(today().plusDays(1));
        assertThat(post(s, desk, base + "/check-out", Map.of()).status()).as("check-out duplicado").isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM housekeeping_logs WHERE room_id = ?", Integer.class, s.roomIds().get(0)))
                .isEqualTo(2);
    }

    @Test
    void arrivalOnlyOnTheCheckInDateAndOnlyWhenConfirmed() {
        Setup s = setup(2);
        long future = id(booking(s, today().plusDays(5), today().plusDays(6), true));
        Res early = post(s, s.token(), "/api/admin/reservations/" + future + "/check-in", checkIn(true, true));
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().path("message").asText()).contains("La llegada es el");

        long pending = id(booking(s, today(), today().plusDays(1), false));
        assertThat(get("/api/admin/reservations/" + pending + "/check-in-preview", s.token(), s.slug()).data()
                .path("blockers").toString()).contains("Confirma");
        assertThat(post(s, s.token(), "/api/admin/reservations/" + pending + "/check-in", checkIn(true, true)).status()).isEqualTo(400);
    }

    @Test
    void aRoomStillBeingCleanedNeedsASupervisorOrAnotherRoom() {
        Setup s = setup(2);
        long id = id(booking(s, today(), today().plusDays(1), true));
        long assigned = jdbc.queryForObject("SELECT room_id FROM reservation_rooms WHERE reservation_id = ?", Long.class, id);
        long other = s.roomIds().stream().filter(r -> r != assigned).findFirst().orElseThrow();
        jdbc.update("UPDATE rooms SET operational_status = 'CLEANING' WHERE id = ?", assigned);
        String desk = receptionist(s);
        String path = "/api/admin/reservations/" + id + "/check-in";

        assertThat(post(s, desk, path, checkIn(true, true)).body().path("message").asText()).contains("en limpieza");
        Map<String, Object> forced = new java.util.HashMap<>(checkIn(true, true));
        forced.put("overrideRoomStatus", true);
        assertThat(post(s, desk, path, forced).status()).isEqualTo(403);

        Map<String, Object> moved = new java.util.HashMap<>(checkIn(true, true));
        moved.put("roomId", other);
        Res res = post(s, desk, path, moved);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        assertThat(res.data().path("summary").path("roomNumber").asText()).isEqualTo("102");
        assertThat(jdbc.queryForObject("SELECT room_id FROM room_occupancies WHERE reservation_id = ?", Long.class, id)).isEqualTo(other);
    }

    @Test
    void noShowChargesThePenaltyOfTheAcceptedPolicyAndFreesTheRoom() {
        Setup s = setup(1);
        long future = id(booking(s, today().plusDays(3), today().plusDays(5), true));
        assertThat(post(s, s.token(), "/api/admin/reservations/" + future + "/no-show", Map.of()).status()).isEqualTo(400);

        long id = id(booking(s, today(), today().plusDays(2), true));
        // La llegada fue ayer.
        jdbc.update("UPDATE reservations SET check_in = check_in - 1, check_out = check_out - 1 WHERE id = ?", id);
        jdbc.update("UPDATE room_occupancies SET stay = daterange(lower(stay) - 1, upper(stay) - 1, '[)') WHERE reservation_id = ?", id);
        String desk = receptionist(s);
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/no-show", Map.of("waivePenalty", true)).status())
                .as("recepción no condona").isEqualTo(403);
        Res res = post(s, desk, "/api/admin/reservations/" + id + "/no-show", Map.of("reason", "No llegó"));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        assertThat(res.data().path("summary").path("status").asText()).isEqualTo("NO_SHOW");
        assertThat(res.data().path("account").path("amountDue").decimalValue()).as("primera noche").isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_occupancies WHERE reservation_id = ?", Integer.class, id)).isZero();
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/no-show", Map.of()).status()).isEqualTo(400);
    }

    @Test
    void roomListTellsWhoIsInEachRoomUntilWhenAndWhoComesNext() {
        Setup s = setup(2);
        String desk = receptionist(s);
        // La 101 (primera en el orden del hotel) recibe las tres: una en casa, otra que llega el día que ésta sale, y una más.
        long inHouse = id(booking(s, today(), today().plusDays(2), true));
        assertThat(post(s, desk, "/api/admin/reservations/" + inHouse + "/check-in", checkIn(true, true)).status()).isEqualTo(200);
        JsonNode next = booking(s, today().plusDays(2), today().plusDays(4), true);
        booking(s, today().plusDays(6), today().plusDays(7), true);
        long cancelled = id(booking(s, today().plusDays(4), today().plusDays(5), true));
        assertThat(post(s, desk, "/api/admin/reservations/" + cancelled + "/cancel", Map.of("reason", "Cambio de planes")).status()).isEqualTo(200);

        JsonNode rooms = get("/api/admin/rooms", desk, s.slug()).data();
        JsonNode r101 = rooms.get(0);
        assertThat(r101.path("number").asText()).isEqualTo("101");
        assertThat(r101.path("status").asText()).isEqualTo("OCCUPIED");
        assertThat(r101.path("currentStay").path("reservationId").asLong()).isEqualTo(inHouse);
        assertThat(r101.path("currentStay").path("guestName").asText()).isEqualTo("María Núñez");
        assertThat(r101.path("currentStay").path("checkOut").asText()).isEqualTo(today().plusDays(2).toString());
        assertThat(r101.path("currentStay").path("guests").asInt()).isEqualTo(2);
        assertThat(r101.path("nextArrival").path("folio").asText()).as("la más próxima, no la cancelada ni la posterior")
                .isEqualTo(next.path("summary").path("folio").asText());
        assertThat(r101.path("nextArrival").path("checkIn").asText()).isEqualTo(today().plusDays(2).toString());
        JsonNode r102 = rooms.get(1);
        assertThat(r102.path("currentStay").isMissingNode()).as("la 102 está vacía").isTrue();
        assertThat(r102.path("nextArrival").isMissingNode()).isTrue();

        // Limpieza ve las fechas (las necesita para saber cuál se desocupa), pero no quién es el huésped.
        JsonNode forHousekeeping = get("/api/admin/rooms", userWith(s, "HOUSEKEEPING:VIEW"), s.slug()).data().get(0);
        assertThat(forHousekeeping.path("currentStay").path("checkOut").asText()).isEqualTo(today().plusDays(2).toString());
        assertThat(forHousekeeping.path("nextArrival").path("checkIn").asText()).isEqualTo(today().plusDays(2).toString());
        assertThat(forHousekeeping.path("currentStay").path("guestName").isMissingNode()).isTrue();
        assertThat(forHousekeeping.path("currentStay").path("folio").isMissingNode()).isTrue();
        assertThat(forHousekeeping.path("currentStay").path("reservationId").isMissingNode()).isTrue();
        assertThat(forHousekeeping.path("nextArrival").path("guestName").isMissingNode()).isTrue();
    }

    @Test
    void aRoomWithAGuestInHouseOnlyLeavesOccupiedThroughCheckOut() {
        Setup s = setup(1);
        long roomId = s.roomIds().get(0);
        String desk = receptionist(s);
        long id = id(booking(s, today(), today().plusDays(1), true));
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/check-in", checkIn(true, true)).status()).isEqualTo(200);
        String status = "/api/admin/rooms/" + roomId + "/status";

        // Ni limpieza ni el administrador la sacan de "Ocupada" a mano: sería saltarse el cobro y el cierre de la estancia.
        for (String target : List.of("CLEANING", "AVAILABLE", "MAINTENANCE")) {
            Res res = call(HttpMethod.PATCH, status, Map.of("status", target), s.token(), s.slug(), null);
            assertThat(res.status()).as(target).isEqualTo(400);
            assertThat(res.body().path("message").asText()).contains("huésped en casa");
        }
        assertThat(roomStatus(roomId)).isEqualTo("OCCUPIED");

        openCash(s, desk, "0");
        assertThat(pay(s, desk, id, "CASH", "1000.00", null, null).status()).isEqualTo(201);
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/check-out", Map.of()).status()).isEqualTo(200);
        assertThat(roomStatus(roomId)).as("el check-out sí la manda a limpieza").isEqualTo("CLEANING");
        assertThat(call(HttpMethod.PATCH, status, Map.of("status", "AVAILABLE"), userWith(s, "HOUSEKEEPING:UPDATE"), s.slug(), null).status())
                .as("ya sin huésped, limpieza la marca lista").isEqualTo(200);
        assertThat(roomStatus(roomId)).isEqualTo("AVAILABLE");
    }
}
