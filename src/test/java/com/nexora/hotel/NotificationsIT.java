package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.service.OperationsAlertService;
import com.nexora.hotel.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Campanita del panel: a quién le llega cada aviso, leídos/no leídos y entrega en vivo (SSE). */
class NotificationsIT extends OperationsTestSupport {

    @Autowired
    private OperationsAlertService operationsAlerts;

    private JsonNode inbox(Setup s, String token) {
        Res res = get("/api/notifications?size=50", token, s.slug());
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data().path("content");
    }

    private static List<String> types(JsonNode content) {
        List<String> types = new ArrayList<>();
        content.forEach(n -> types.add(n.path("type").asText()));
        return types;
    }

    private long unread(Setup s, String token) {
        return get("/api/notifications/unread-count", token, s.slug()).data().path("unread").asLong();
    }

    private long bookAs(Setup s, String token) {
        Map<String, Object> body = new HashMap<>(Map.of("guest", guest("g" + System.nanoTime() + "@mail.mx"),
                "roomTypeId", s.typeId(), "checkIn", today().plusDays(3).toString(), "checkOut", today().plusDays(5).toString(),
                "adults", 2, "children", 0, "source", "PHONE", "confirmNow", false));
        Res res = post(s, token, "/api/admin/reservations", body);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(201);
        return id(res.data());
    }

    @Test
    void noticesGoToWhoeverCanActOnThemButNotToWhoeverDidIt() {
        Setup s = setup(2);
        String desk = receptionist(s);
        String noReservations = userWith(s, "AVAILABILITY:VIEW");

        long id = bookAs(s, desk);

        JsonNode admin = inbox(s, s.token());
        assertThat(types(admin)).containsExactly("RESERVATION_CREATED");
        JsonNode n = admin.get(0);
        assertThat(n.path("category").asText()).isEqualTo("RESERVATIONS");
        assertThat(n.path("link").asText()).isEqualTo("/panel/reservaciones?r=" + id);
        assertThat(n.path("params").path("status").asText()).isEqualTo("PENDING");
        assertThat(n.path("params").path("channel").asText()).isEqualTo("STAFF");
        assertThat(n.path("params").path("folio").asText()).startsWith("NXH-");
        assertThat(n.path("read").asBoolean()).isFalse();

        assertThat(inbox(s, desk)).as("quien la capturó no recibe su propio aviso").isEmpty();
        assertThat(inbox(s, noReservations)).as("sin permiso de reservaciones no se entera").isEmpty();

        // El admin confirma: recepción sí se entera (el admin no).
        assertThat(post(s, s.token(), "/api/admin/reservations/" + id + "/confirm", Map.of()).status()).isEqualTo(200);
        assertThat(types(inbox(s, desk))).containsExactly("RESERVATION_CONFIRMED");
        assertThat(types(inbox(s, s.token()))).containsExactly("RESERVATION_CREATED");

        // Cancelación por recepción: le llega al admin con el motivo.
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/cancel", Map.of("reason", "Cambio de planes")).status())
                .isEqualTo(200);
        JsonNode cancelled = inbox(s, s.token()).get(0);
        assertThat(cancelled.path("type").asText()).isEqualTo("RESERVATION_CANCELLED");
        assertThat(cancelled.path("params").path("reason").asText()).isEqualTo("Cambio de planes");
        assertThat(cancelled.path("params").path("by").asText()).isEqualTo("STAFF");
        assertThat(cancelled.path("severity").asText()).isEqualTo("WARNING");
    }

    @Test
    void readStateIsPerPersonAndFilterable() {
        Setup s = setup(3);
        String desk = receptionist(s);
        bookAs(s, desk);
        bookAs(s, desk);
        assertThat(unread(s, s.token())).isEqualTo(2);

        long first = inbox(s, s.token()).get(0).path("id").asLong();
        Res read = post(s, s.token(), "/api/notifications/" + first + "/read", Map.of());
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.data().path("unread").asLong()).isEqualTo(1);
        assertThat(get("/api/notifications?unread=true", s.token(), s.slug()).data().path("totalElements").asLong()).isEqualTo(1);
        assertThat(get("/api/notifications?category=PAYMENTS", s.token(), s.slug()).data().path("content")).isEmpty();
        assertThat(get("/api/notifications?category=RESERVATIONS", s.token(), s.slug()).data().path("totalElements").asLong())
                .isEqualTo(2);
        assertThat(get("/api/notifications?category=OTRA", s.token(), s.slug()).status()).isEqualTo(400);

        // Nadie marca (ni ve) los avisos de otro.
        assertThat(post(s, desk, "/api/notifications/" + first + "/read", Map.of()).status()).isEqualTo(404);

        assertThat(post(s, s.token(), "/api/notifications/read-all", Map.of()).data().path("unread").asLong()).isZero();
        assertThat(unread(s, s.token())).isZero();
    }

    @Test
    void onlyHotelStaffHasABell() {
        Setup s = setup(1);
        assertThat(get("/api/notifications", null, s.slug()).status()).isEqualTo(401);
        assertThat(get("/api/notifications/unread-count", userWith(s, "AVAILABILITY:VIEW"), s.slug()).status())
                .as("personal sin permisos especiales sí tiene campanita (vacía)").isEqualTo(200);
    }

    @Test
    void liveStreamDeliversTheNoticeAsSoonAsItHappens() throws Exception {
        Setup s = setup(2);
        String desk = receptionist(s);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url("/api/notifications/stream")))
                .header("Authorization", "Bearer " + s.token()).header("X-Hotel", s.slug())
                .header("Accept", "text/event-stream").GET().build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            Map<String, String> ready = nextEvent(reader);
            assertThat(ready.get("event")).isEqualTo("ready");
            assertThat(objectMapper.readTree(ready.get("data")).path("unread").asLong()).isZero();

            long id = bookAs(s, desk);

            Map<String, String> live = CompletableFuture.supplyAsync(() -> nextEvent(reader)).get(10, TimeUnit.SECONDS);
            assertThat(live.get("event")).isEqualTo("notification");
            JsonNode payload = objectMapper.readTree(live.get("data"));
            assertThat(payload.path("unread").asLong()).isEqualTo(1);
            assertThat(payload.path("notification").path("type").asText()).isEqualTo("RESERVATION_CREATED");
            assertThat(payload.path("notification").path("link").asText()).isEqualTo("/panel/reservaciones?r=" + id);

            // Marcar como leída en otra pestaña sincroniza el contador de ésta.
            post(s, s.token(), "/api/notifications/read-all", Map.of());
            Map<String, String> synced = CompletableFuture.supplyAsync(() -> nextEvent(reader)).get(10, TimeUnit.SECONDS);
            assertThat(synced.get("event")).isEqualTo("unread");
            assertThat(objectMapper.readTree(synced.get("data")).path("unread").asLong()).isZero();
        }
    }

    @Test
    void dailyOperationAlertsAreSentOncePerGuest() {
        Setup s = setup(2);
        // Huésped en casa cuya salida fue ayer (se ajustan las fechas directo: el alta no admite fechas pasadas).
        long late = id(booking(s, today().plusDays(1), today().plusDays(3), true));
        jdbc.update("UPDATE reservations SET status = 'IN_HOUSE', check_in = ?, check_out = ? WHERE id = ?",
                today().minusDays(3), today().minusDays(1), late);

        TenantContext.runAs(s.hotel().id(), () -> operationsAlerts.check());
        TenantContext.runAs(s.hotel().id(), () -> operationsAlerts.check());

        JsonNode admin = inbox(s, s.token());
        assertThat(types(admin)).containsOnlyOnce("LATE_CHECKOUT");
        JsonNode lateNotice = admin.get(types(admin).indexOf("LATE_CHECKOUT"));
        assertThat(lateNotice.path("category").asText()).isEqualTo("OPERATIONS");
        assertThat(lateNotice.path("params").path("room").asText()).isNotBlank();
        assertThat(lateNotice.path("link").asText()).isEqualTo("/panel/reservaciones?r=" + late);
    }

    @Test
    void roomReadyIsAnnouncedWhenSomeoneArrivesToItToday() {
        Setup s = setup(1);
        long roomId = s.roomIds().get(0);
        long arrival = id(booking(s, today(), today().plusDays(1), true));
        jdbc.update("UPDATE rooms SET operational_status = 'CLEANING' WHERE id = ?", roomId);
        String housekeeping = userWith(s, "HOUSEKEEPING:UPDATE");

        Res res = call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "AVAILABLE"),
                housekeeping, s.slug(), null);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);

        JsonNode ready = inbox(s, s.token()).get(0);
        assertThat(ready.path("type").asText()).isEqualTo("ROOM_READY");
        assertThat(ready.path("params").path("room").asText()).isEqualTo("101");
        assertThat(ready.path("link").asText()).isEqualTo("/panel/reservaciones?r=" + arrival);
    }

    @Test
    void checkOutTellsHousekeepingAndOnlyHousekeepingThatTheRoomNeedsCleaning() {
        Setup s = setup(1);
        long roomId = s.roomIds().get(0);
        String desk = receptionist(s);
        // Todo hotel nace con el rol Camarista: solo limpieza.
        assertThat(jdbc.queryForList("""
                SELECT p.code FROM roles r JOIN role_permissions rp ON rp.role_id = r.id JOIN permissions p ON p.id = rp.permission_id
                WHERE r.hotel_id = ? AND r.code = 'HOUSEKEEPER' AND r.system ORDER BY 1""", String.class, s.hotel().id()))
                .containsExactly("HOUSEKEEPING:UPDATE", "HOUSEKEEPING:VIEW");
        String housekeeper = token(createUser(s.hotel(), "HOUSEKEEPER").getEmail(), s.slug());

        long id = id(booking(s, today(), today().plusDays(1), true));
        Map<String, Object> checkIn = Map.of("identityVerified", true, "acceptPendingBalance", true,
                "guests", List.of(Map.of("fullName", "María Núñez", "minor", false, "idType", "INE", "idNumber", "NUMA800101")));
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/check-in", checkIn).status()).isEqualTo(200);
        openCash(s, desk, "0");
        assertThat(pay(s, desk, id, "CASH", "1000.00", null, null).status()).isEqualTo(201);
        assertThat(inbox(s, housekeeper)).as("mientras el huésped está en casa no hay nada que limpiar").isEmpty();
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/check-out", Map.of()).status()).isEqualTo(200);

        JsonNode notice = inbox(s, housekeeper).get(0);
        assertThat(notice.path("type").asText()).isEqualTo("ROOM_TO_CLEAN");
        assertThat(notice.path("params").path("room").asText()).isEqualTo("101");
        assertThat(notice.path("params").path("arrivalToday").asText()).isEqualTo("false");
        assertThat(notice.path("link").asText()).isEqualTo("/panel/habitaciones?tab=limpieza");
        assertThat(notice.path("params").has("guest")).as("limpieza no recibe el nombre del huésped").isFalse();
        // El mostrador registra las salidas: no necesita un aviso por cada una.
        assertThat(types(inbox(s, s.token()))).doesNotContain("ROOM_TO_CLEAN");
        assertThat(types(inbox(s, desk))).doesNotContain("ROOM_TO_CLEAN");

        // La camarista la marca lista; no ve reservaciones ni puede tocar otra cosa.
        assertThat(call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "AVAILABLE"), housekeeper, s.slug(), null).status())
                .isEqualTo(200);
        assertThat(get("/api/admin/reservations/board", housekeeper, s.slug()).status()).isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "MAINTENANCE"), housekeeper, s.slug(), null).status())
                .isEqualTo(403);
    }

    /** Lee un evento SSE completo (hasta la línea en blanco); ignora los comentarios de latido. */
    private static Map<String, String> nextEvent(BufferedReader reader) {
        Map<String, String> event = new HashMap<>();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!event.isEmpty()) {
                        return event;
                    }
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1).stripLeading();
                event.merge(field, value, (a, b) -> a + "\n" + b);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("La conexión se cerró sin evento");
    }
}
