package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.notification.OutboxProcessor;
import com.nexora.hotel.service.ReservationService;
import com.nexora.hotel.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 6: reservaciones (prompt §29 · Reservaciones). */
class ReservationIT extends SecurityTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private OutboxProcessor outboxProcessor;

    private static final LocalDate IN = LocalDate.now().plusDays(40);

    /** Hotel con un tipo "DBL" de {@code rooms} habitaciones a $1,000 la noche. */
    private record Setup(TestHotel hotel, String token, long typeId, List<Long> roomIds) {
    }

    private Setup setup(int rooms) {
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
        // Sin impuestos para que los importes de las pruebas sean exactos.
        jdbc.update("UPDATE tax_rates SET active = false WHERE hotel_id = ?", hotel.id());
        return new Setup(hotel, token, typeId, ids);
    }

    private static Map<String, Object> guest(String email) {
        return Map.of("firstName", "María", "lastName", "Núñez", "email", email, "phoneCountryCode", "52", "phone", "9981234567");
    }

    private Res staffBooking(Setup s, LocalDate in, LocalDate out, int adults, boolean confirm) {
        Map<String, Object> body = new HashMap<>(Map.of("guest", guest("maria" + System.nanoTime() + "@mail.mx"),
                "roomTypeId", s.typeId(), "checkIn", in.toString(), "checkOut", out.toString(), "adults", adults,
                "children", 0, "source", "FRONT_DESK", "confirmNow", confirm));
        return call(HttpMethod.POST, "/api/admin/reservations", body, s.token(), s.hotel().slug(), null);
    }

    private Res publicBooking(Setup s, LocalDate in, LocalDate out, String email) {
        Map<String, Object> body = new HashMap<>(Map.of("roomTypeId", s.typeId(), "checkIn", in.toString(), "checkOut", out.toString(),
                "adults", 2, "children", 0, "guest", guest(email), "acceptTerms", true, "acceptPrivacy", true));
        return call(HttpMethod.POST, "/api/public/reservations", body, null, s.hotel().slug(), null);
    }

    private Res guestCall(HttpMethod method, String path, Object body, String slug, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hotel", slug);
        if (token != null) {
            headers.set("X-Reservation-Token", token);
        }
        var response = restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
        try {
            return new Res(response.getStatusCode().value(), objectMapper.readTree(response.getBody()), response.getHeaders());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void staffBookingAssignsRoomPricesAndQueuesEmail() {
        Setup s = setup(2);
        Res res = staffBooking(s, IN, IN.plusDays(3), 3, true);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(201);
        JsonNode d = res.data();
        assertThat(d.path("summary").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(d.path("summary").path("folio").asText()).matches("^NXH-[A-Z2-9]{6}$");
        assertThat(d.path("summary").path("roomNumber").asText()).isEqualTo("101");
        // 3 noches × 1000 + 1 adulto extra × 300 × 3 = 3900
        assertThat(d.path("summary").path("total").decimalValue()).isEqualByComparingTo("3900.00");
        assertThat(d.path("priceLines").size()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE related_id = ?", Integer.class,
                d.path("summary").path("id").asLong())).isEqualTo(1);

        // La cola es compartida por todas las pruebas: se procesa por lotes hasta llegar a este correo.
        long related = d.path("summary").path("id").asLong();
        for (int i = 0; i < 50 && "PENDING".equals(jdbc.queryForObject(
                "SELECT status FROM notification_outbox WHERE related_id = ?", String.class, related)); i++) {
            outboxProcessor.processBatch();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox WHERE related_id = ?", String.class,
                d.path("summary").path("id").asLong())).as("sin SMTP no se simula el envío").isEqualTo("SKIPPED");
    }

    @Test
    void sameDayBookingAfterTheCheckInHourRespectsTheConfiguredLeadTime() {
        Setup s = setup(2);
        LocalDate today = LocalDate.now(java.time.ZoneId.of("America/Mexico_City"));
        // Check-in a medianoche: "ahora" siempre es posterior a la hora de llegada de hoy.
        jdbc.update("UPDATE hotel_settings SET check_in_time = '00:00', min_booking_lead_hours = 0, allow_same_day_booking = TRUE "
                + "WHERE hotel_id = ?", s.hotel().id());
        assertThat(call(HttpMethod.GET, "/api/public/hotel", null, null, s.hotel().slug(), null).data().path("checkInTime").asText())
                .as("la hora guardada se lee igual, sin corrimiento por la zona de la JVM").isEqualTo("00:00:00");
        Res search = call(HttpMethod.GET, "/api/public/availability?checkIn=" + today + "&checkOut=" + today.plusDays(1), null, null, s.hotel().slug(), null);
        assertThat(search.status()).as("búsqueda del mismo día: " + search.body()).isEqualTo(200);
        Res sameDay = publicBooking(s, today, today.plusDays(1), "hoy" + System.nanoTime() + "@mail.mx");
        assertThat(sameDay.status()).as("sin anticipación mínima se puede reservar hoy: " + sameDay.body()).isEqualTo(201);

        jdbc.update("UPDATE hotel_settings SET min_booking_lead_hours = 2 WHERE hotel_id = ?", s.hotel().id());
        Res late = publicBooking(s, today, today.plusDays(1), "tarde" + System.nanoTime() + "@mail.mx");
        assertThat(late.status()).as(late.body().toString()).isEqualTo(400);
        assertThat(late.body().path("message").asText()).contains("2 horas");
    }

    @Test
    void invalidDatesCapacityAndPastAreRejected() {
        Setup s = setup(1);
        assertThat(staffBooking(s, IN, IN, 2, false).status()).isEqualTo(400);
        assertThat(staffBooking(s, IN.plusDays(2), IN, 2, false).status()).isEqualTo(400);
        assertThat(staffBooking(s, LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), 2, false).status()).isEqualTo(400);
        Res tooMany = staffBooking(s, IN, IN.plusDays(1), 4, false);
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.body().path("message").asText()).contains("admite hasta 3");
    }

    @Test
    void twoGuestsBookingTheLastRoomAtTheSameTimeOnlyOneWins() throws Exception {
        Setup s = setup(1);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            attempts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return publicBooking(s, IN.plusDays(5), IN.plusDays(8), "huesped" + n + "@mail.mx").status();
            }));
        }
        start.countDown();
        List<Integer> statuses = attempts.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).filteredOn(st -> st == 201).hasSize(1);
        assertThat(statuses).filteredOn(st -> st == 409).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_occupancies WHERE room_id = ?", Integer.class, s.roomIds().get(0))).isEqualTo(1);
    }

    @Test
    void roomUnderMaintenanceCannotBeBooked() {
        Setup s = setup(1);
        assertThat(call(HttpMethod.POST, "/api/admin/rooms/" + s.roomIds().get(0) + "/blocks",
                Map.of("startsOn", IN.toString(), "endsOn", IN.plusDays(2).toString(), "reason", "Pintura"), s.token(), s.hotel().slug(), null)
                .status()).isEqualTo(201);
        assertThat(staffBooking(s, IN.plusDays(1), IN.plusDays(3), 2, false).status()).isEqualTo(409);
        JsonNode search = get("/api/public/availability?checkIn=" + IN.plusDays(1) + "&checkOut=" + IN.plusDays(3) + "&adults=2", null,
                s.hotel().slug()).data();
        assertThat(search.path("options").size()).as("el portal no ofrece lo que no hay").isZero();
        // El día de salida del bloqueo ya se puede ocupar.
        assertThat(staffBooking(s, IN.plusDays(2), IN.plusDays(4), 2, false).status()).isEqualTo(201);
    }

    @Test
    void cancellationInsideAndOutsideTheFreeWindow() {
        Setup s = setup(1);
        long early = staffBooking(s, IN, IN.plusDays(2), 2, true).data().path("summary").path("id").asLong();
        Res free = call(HttpMethod.POST, "/api/admin/reservations/" + early + "/cancel", Map.of("reason", "Cambio de planes"),
                s.token(), s.hotel().slug(), null);
        assertThat(free.status()).isEqualTo(200);
        assertThat(free.data().path("penaltyAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(staffBooking(s, IN, IN.plusDays(2), 2, true).status()).as("la habitación se liberó").isEqualTo(201);

        // Llegada mañana: dentro de las 48 h → penalización de primera noche (política por defecto).
        LocalDate tomorrow = LocalDate.now(java.time.ZoneId.of("America/Mexico_City")).plusDays(1);
        long late = staffBooking(s, tomorrow, tomorrow.plusDays(2), 2, true).data().path("summary").path("id").asLong();
        JsonNode preview = get("/api/admin/reservations/" + late, s.token(), s.hotel().slug()).data().path("cancellation");
        assertThat(preview.path("outcome").asText()).isEqualTo("WITH_PENALTY");
        Res penalized = call(HttpMethod.POST, "/api/admin/reservations/" + late + "/cancel", Map.of("reason", "No llegará"),
                s.token(), s.hotel().slug(), null);
        assertThat(penalized.data().path("penaltyAmount").decimalValue()).isEqualByComparingTo("1000.00");
    }

    @Test
    void receptionistCannotWaivePenalties() {
        Setup s = setup(1);
        LocalDate tomorrow = LocalDate.now(java.time.ZoneId.of("America/Mexico_City")).plusDays(1);
        long id = staffBooking(s, tomorrow, tomorrow.plusDays(1), 2, true).data().path("summary").path("id").asLong();
        User receptionist = createUser(s.hotel(), Role.RECEPTIONIST);
        String reception = token(receptionist.getEmail(), s.hotel().slug());
        assertThat(call(HttpMethod.POST, "/api/admin/reservations/" + id + "/cancel", Map.of("reason", "x", "waivePenalty", true),
                reception, s.hotel().slug(), null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/api/admin/reservations/" + id + "/cancel", Map.of("reason", "x"),
                reception, s.hotel().slug(), null).status()).isEqualTo(200);
    }

    @Test
    void repeatedConfirmationIsRejected() {
        Setup s = setup(1);
        long id = staffBooking(s, IN, IN.plusDays(1), 2, false).data().path("summary").path("id").asLong();
        assertThat(call(HttpMethod.POST, "/api/admin/reservations/" + id + "/confirm", null, s.token(), s.hotel().slug(), null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/admin/reservations/" + id + "/confirm", null, s.token(), s.hotel().slug(), null).status()).isEqualTo(400);
    }

    @Test
    void expiredPendingRequestsReleaseTheRoom() {
        Setup s = setup(1);
        Res created = publicBooking(s, IN, IN.plusDays(2), "pendiente@mail.mx");
        assertThat(created.data().path("status").asText()).isEqualTo("PENDING");
        jdbc.update("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE hotel_id = ?", s.hotel().id());

        int expired = TenantContext.callAs(s.hotel().id(), () -> reservationService.expireOverdue());
        assertThat(expired).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE hotel_id = ?", String.class, s.hotel().id())).isEqualTo("EXPIRED");
        assertThat(publicBooking(s, IN, IN.plusDays(2), "otro@mail.mx").status()).isEqualTo(201);
    }

    @Test
    void changingDatesRecalculatesAndKeepsOriginalIfUnavailable() {
        Setup s = setup(1);
        long id = staffBooking(s, IN, IN.plusDays(2), 2, true).data().path("summary").path("id").asLong();
        staffBooking(s, IN.plusDays(10), IN.plusDays(12), 2, true);

        Res moved = call(HttpMethod.PUT, "/api/admin/reservations/" + id, Map.of("roomTypeId", s.typeId(), "checkIn", IN.plusDays(1).toString(),
                "checkOut", IN.plusDays(5).toString(), "adults", 2, "children", 0, "reason", "Extiende estancia"), s.token(), s.hotel().slug(), null);
        assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
        assertThat(moved.data().path("summary").path("total").decimalValue()).isEqualByComparingTo("4000.00");
        assertThat(moved.data().path("priceRevision").asInt()).isEqualTo(2);

        Res clash = call(HttpMethod.PUT, "/api/admin/reservations/" + id, Map.of("roomTypeId", s.typeId(), "checkIn", IN.plusDays(9).toString(),
                "checkOut", IN.plusDays(11).toString(), "adults", 2, "children", 0, "reason", "Cambio"), s.token(), s.hotel().slug(), null);
        assertThat(clash.status()).isEqualTo(409);
        JsonNode after = get("/api/admin/reservations/" + id, s.token(), s.hotel().slug()).data();
        assertThat(after.path("summary").path("checkIn").asText()).isEqualTo(IN.plusDays(1).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM room_occupancies WHERE reservation_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void guestAccessRequiresFolioAndTokenOfTheSameHotel() {
        Setup s = setup(1);
        Setup other = setup(1);
        Res created = publicBooking(s, IN, IN.plusDays(2), "invitado@mail.mx");
        String folio = created.data().path("folio").asText();
        String token = created.data().path("accessToken").asText();

        assertThat(guestCall(HttpMethod.GET, "/api/public/reservations/" + folio, null, s.hotel().slug(), token).status()).isEqualTo(200);
        assertThat(guestCall(HttpMethod.GET, "/api/public/reservations/" + folio, null, s.hotel().slug(), null).status()).isEqualTo(404);
        assertThat(guestCall(HttpMethod.GET, "/api/public/reservations/" + folio, null, s.hotel().slug(),
                "x".repeat(43)).status()).isEqualTo(404);
        assertThat(guestCall(HttpMethod.GET, "/api/public/reservations/" + folio, null, other.hotel().slug(), token).status()).isEqualTo(404);

        Res cancelled = guestCall(HttpMethod.POST, "/api/public/reservations/" + folio + "/cancel", Map.of("reason", "Ya no viajo"),
                s.hotel().slug(), token);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.data().path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void publicFormRequiresConsentAndRejectsBots() {
        Setup s = setup(1);
        Map<String, Object> body = new HashMap<>(Map.of("roomTypeId", s.typeId(), "checkIn", IN.toString(), "checkOut", IN.plusDays(1).toString(),
                "adults", 2, "children", 0, "guest", guest("a@mail.mx"), "acceptTerms", false, "acceptPrivacy", true));
        assertThat(call(HttpMethod.POST, "/api/public/reservations", body, null, s.hotel().slug(), null).status()).isEqualTo(400);
        body.put("acceptTerms", true);
        body.put("website", "http://spam");
        assertThat(call(HttpMethod.POST, "/api/public/reservations", body, null, s.hotel().slug(), null).status()).isEqualTo(400);
        Map<String, Object> badPhone = new HashMap<>(guest("b@mail.mx"));
        badPhone.put("phone", "99812345678");
        body.remove("website");
        body.put("guest", badPhone);
        assertThat(call(HttpMethod.POST, "/api/public/reservations", body, null, s.hotel().slug(), null).status()).isEqualTo(400);
    }

    @Test
    void lookupAnswersTheSameWhetherOrNotTheReservationExists() {
        Setup s = setup(1);
        String folio = publicBooking(s, IN, IN.plusDays(1), "real@mail.mx").data().path("folio").asText();
        Res real = call(HttpMethod.POST, "/api/public/reservations/lookup", Map.of("folio", folio, "email", "real@mail.mx"), null, s.hotel().slug(), null);
        Res fake = call(HttpMethod.POST, "/api/public/reservations/lookup", Map.of("folio", "NXH-AAAAAA", "email", "x@mail.mx"), null, s.hotel().slug(), null);
        assertThat(real.status()).isEqualTo(200);
        assertThat(fake.status()).isEqualTo(200);
        assertThat(real.body().path("message").asText()).isEqualTo(fake.body().path("message").asText());
    }

    @Test
    void publicPoliciesExposeOnlyWhatTheGuestAccepts() {
        Setup s = setup(1);
        Res res = call(HttpMethod.GET, "/api/public/policies", null, null, s.hotel().slug(), null);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().path("data").path("values").has("freeCancelHours")).isTrue();
        assertThat(res.body().path("data").path("values").path("lateCancelPenaltyType").asText()).isNotBlank();
        assertThat(call(HttpMethod.GET, "/api/public/policies", null, null, null, null).status()).isIn(400, 404);
    }
}
