package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 8: cuentas de huésped (registro verificado, mis reservaciones, pagos, comprobantes y datos personales). */
class GuestAccountIT extends OperationsTestSupport {

    private static final String GUEST_PASSWORD = "Huesped12345";
    private static final Pattern VERIFY = Pattern.compile("/cuenta/verificar/([A-Za-z0-9_-]{20,})");

    private Res register(Setup s, String email) {
        return call(HttpMethod.POST, "/api/public/account/register", Map.of("firstName", "Lucía", "lastName", "Méndez",
                "email", email, "phoneCountryCode", "52", "phone", "9982223344", "password", GUEST_PASSWORD, "acceptPrivacy", true),
                null, s.slug(), null);
    }

    /** El enlace de confirmación sale por la cola de correos del hotel: se toma de ahí, como lo recibiría el huésped. */
    private String verificationToken(Setup s, String email) {
        String html = jdbc.queryForObject("""
                SELECT body_html FROM notification_outbox WHERE hotel_id = ? AND recipient = ? AND event_code = 'ACCOUNT_VERIFY'
                ORDER BY id DESC LIMIT 1""", String.class, s.hotel().id(), email);
        Matcher m = VERIFY.matcher(html);
        assertThat(m.find()).as("el correo trae el enlace").isTrue();
        return m.group(1);
    }

    private String verifiedGuest(Setup s, String email) {
        assertThat(register(s, email).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/public/account/verify", Map.of("token", verificationToken(s, email)), null, s.slug(), null)
                .status()).isEqualTo(200);
        Res login = login(email, GUEST_PASSWORD, s.slug());
        assertThat(login.status()).as(login.body().toString()).isEqualTo(200);
        return login.data().path("accessToken").asText();
    }

    private Res publicBooking(Setup s, String email, String token) {
        Map<String, Object> body = new HashMap<>(Map.of("roomTypeId", s.typeId(), "checkIn", today().plusDays(30).toString(),
                "checkOut", today().plusDays(31).toString(), "adults", 2, "children", 0, "guest", guest(email),
                "acceptTerms", true, "acceptPrivacy", true));
        return call(HttpMethod.POST, "/api/public/reservations", body, token, s.slug(), null);
    }

    @Test
    void anAccountOnlyWorksAfterConfirmingTheEmail() {
        Setup s = setup(1);
        Res first = register(s, "lucia@mail.mx");
        assertThat(first.status()).isEqualTo(200);
        Res login = login("lucia@mail.mx", GUEST_PASSWORD, s.slug());
        assertThat(login.status()).isEqualTo(400);
        assertThat(login.body().path("message").asText()).contains("Confirma tu correo");

        Res again = register(s, "lucia@mail.mx");
        assertThat(again.body().path("message").asText()).as("misma respuesta: no revela si existe").isEqualTo(first.body().path("message").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE hotel_id = ? AND email = 'lucia@mail.mx'", Integer.class, s.hotel().id())).isEqualTo(1);

        assertThat(call(HttpMethod.POST, "/api/public/account/verify", Map.of("token", "x".repeat(40)), null, s.slug(), null).status()).isEqualTo(400);
        String token = verificationToken(s, "lucia@mail.mx");
        assertThat(call(HttpMethod.POST, "/api/public/account/verify", Map.of("token", token), null, s.slug(), null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/public/account/verify", Map.of("token", token), null, s.slug(), null).status())
                .as("el enlace se usa una sola vez").isEqualTo(400);
        String access = login("lucia@mail.mx", GUEST_PASSWORD, s.slug()).data().path("accessToken").asText();
        assertThat(get("/api/account/profile", access, s.slug()).data().path("firstName").asText()).isEqualTo("Lucía");
        assertThat(get("/api/admin/overview", access, s.slug()).status()).as("un huésped no entra al panel").isEqualTo(403);
        assertThat(register(s, "admin@" + s.slug() + ".mx").status()).as("no revela cuentas del personal").isEqualTo(200);
    }

    @Test
    void pastReservationsWithTheSameEmailAppearOnlyAfterVerifying() {
        Setup s = setup(1);
        String folio = publicBooking(s, "pepe@mail.mx", null).data().path("folio").asText();
        String access = verifiedGuest(s, "pepe@mail.mx");
        JsonNode mine = get("/api/account/reservations", access, s.slug()).data();
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("folio").asText()).isEqualTo(folio);
        assertThat(mine.get(0).path("upcoming").asBoolean()).isTrue();
        assertThat(get("/api/account/reservations/" + folio, access, s.slug()).data().path("status").asText()).isEqualTo("PENDING");

        String other = verifiedGuest(s, "otra@mail.mx");
        assertThat(get("/api/account/reservations/" + folio, other, s.slug()).status()).as("no ve reservaciones ajenas").isEqualTo(404);
        assertThat(get("/api/account/reservations/" + folio + "/receipt.pdf", other, s.slug()).status()).isEqualTo(404);
        var pdf = restTemplate.exchange(url("/api/account/reservations/" + folio + "/receipt.pdf"), HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(authHeaders(access, s.slug())), byte[].class);
        assertThat(pdf.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(pdf.getBody(), 0, 4)).isEqualTo("%PDF");

        Res cancelled = call(HttpMethod.POST, "/api/account/reservations/" + folio + "/cancel", Map.of("reason", "Cambio de planes"),
                access, s.slug(), null);
        assertThat(cancelled.data().path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void aSignedInGuestBooksIntoTheirOwnProfile() {
        Setup s = setup(1);
        String access = verifiedGuest(s, "ana.cuenta@mail.mx");
        String folio = publicBooking(s, "correo-distinto@mail.mx", access).data().path("folio").asText();
        assertThat(get("/api/account/reservations", access, s.slug()).data().get(0).path("folio").asText()).isEqualTo(folio);
        long payments = get("/api/account/payments", access, s.slug()).data().size();
        assertThat(payments).isZero();
    }

    @Test
    void deletionRequestAnonymizesPersonalDataButKeepsTheRecords() {
        Setup s = setup(1);
        String access = verifiedGuest(s, "borrar@mail.mx");
        String folio = publicBooking(s, "borrar@mail.mx", access).data().path("folio").asText();
        JsonNode export = get("/api/account/data-export", access, s.slug()).body();
        assertThat(export.toString()).contains(folio).contains("borrar@mail.mx");

        Res request = call(HttpMethod.POST, "/api/account/data-requests", Map.of("type", "DELETION", "details", "Ya no quiero mi cuenta"),
                access, s.slug(), null);
        assertThat(request.status()).isEqualTo(201);
        assertThat(call(HttpMethod.POST, "/api/account/data-requests", Map.of("type", "DELETION"), access, s.slug(), null).status())
                .as("una sola solicitud en trámite por tipo").isEqualTo(400);
        long requestId = request.data().path("id").asLong();
        assertThat(get("/api/admin/data-requests?status=PENDING", s.token(), s.slug()).data().size()).isEqualTo(1);

        String resolve = "/api/admin/data-requests/" + requestId + "/resolve";
        Res blocked = post(s, s.token(), resolve, Map.of("status", "COMPLETED", "notes", "Eliminado", "anonymize", true));
        assertThat(blocked.status()).as("tiene una reservación activa").isEqualTo(400);
        call(HttpMethod.POST, "/api/account/reservations/" + folio + "/cancel", Map.of("reason", "Baja"), access, s.slug(), null);
        Res done = post(s, s.token(), resolve, Map.of("status", "COMPLETED", "notes", "Datos anonimizados a solicitud del titular", "anonymize", true));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);

        assertThat(login("borrar@mail.mx", GUEST_PASSWORD, s.slug()).status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT contact_email FROM reservations WHERE folio = ? AND hotel_id = ?", String.class, folio, s.hotel().id())).isNull();
        assertThat(jdbc.queryForObject("SELECT total FROM reservations WHERE folio = ? AND hotel_id = ?", java.math.BigDecimal.class, folio, s.hotel().id()))
                .as("el registro financiero se conserva").isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE hotel_id = ? AND email = 'borrar@mail.mx'", Integer.class, s.hotel().id())).isZero();
        assertThat(post(s, s.token(), resolve, Map.of("status", "REJECTED", "notes", "x")).status()).isEqualTo(400);
    }

    @Test
    void theGuestCanChooseToPayAtTheHotel() {
        Setup s = setup(1);
        call(HttpMethod.PUT, "/api/admin/payment-settings", Map.of("cashEnabled", true, "cardEnabled", true, "transferEnabled", true,
                "onlineEnabled", true, "sandbox", true, "merchantId", OpenpayStub.MERCHANT, "privateKey", OpenpayStub.PRIVATE_KEY),
                s.token(), s.slug(), null);
        Map<String, Object> body = new HashMap<>(Map.of("roomTypeId", s.typeId(), "checkIn", today().plusDays(30).toString(),
                "checkOut", today().plusDays(31).toString(), "adults", 2, "children", 0, "guest", guest("hotel@mail.mx"),
                "acceptTerms", true, "acceptPrivacy", true, "payOnline", false));
        JsonNode atHotel = call(HttpMethod.POST, "/api/public/reservations", body, null, s.slug(), null).data();
        assertThat(atHotel.path("status").asText()).isEqualTo("PENDING");
        assertThat(atHotel.path("paymentRequired").asBoolean()).isFalse();
        body.put("payOnline", true);
        body.put("checkIn", today().plusDays(35).toString());
        body.put("checkOut", today().plusDays(36).toString());
        assertThat(call(HttpMethod.POST, "/api/public/reservations", body, null, s.slug(), null).data().path("status").asText())
                .isEqualTo("PENDING_PAYMENT");
    }

    private org.springframework.http.HttpHeaders authHeaders(String token, String slug) {
        var h = new org.springframework.http.HttpHeaders();
        h.setBearerAuth(token);
        h.set("X-Hotel", slug);
        return h;
    }
}
