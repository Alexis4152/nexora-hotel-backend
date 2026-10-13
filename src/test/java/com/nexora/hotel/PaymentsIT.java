package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 7: cobros en recepción, idempotencia, reversos, reembolsos, caja y pago en línea con Openpay (simulador local). */
class PaymentsIT extends OperationsTestSupport {

    private static final java.time.LocalDate FUTURE = today().plusDays(40);

    private JsonNode account(Setup s, long reservationId) {
        return get("/api/admin/reservations/" + reservationId, s.token(), s.slug()).data().path("account");
    }

    // ── Recepción ──────────────────────────────────────────────────────────────────────

    @Test
    void cashPaymentNeedsAnOpenCashAndNeverExceedsTheBalance() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));

        Res noCash = pay(s, s.token(), id, "CASH", "400.00", null, null);
        assertThat(noCash.status()).isEqualTo(400);
        assertThat(noCash.body().path("message").asText()).contains("Abre tu caja");

        assertThat(openCash(s, s.token(), "500.00").status()).isEqualTo(201);
        assertThat(openCash(s, s.token(), "100.00").status()).as("una sola caja abierta por usuario").isEqualTo(409);
        Res paid = pay(s, s.token(), id, "CASH", "400.00", null, null);
        assertThat(paid.status()).as(paid.body().toString()).isEqualTo(201);
        JsonNode acc = paid.data().path("account");
        assertThat(acc.path("paid").decimalValue()).isEqualByComparingTo("400");
        assertThat(acc.path("balance").decimalValue()).isEqualByComparingTo("600");

        Res over = pay(s, s.token(), id, "CASH", "700.00", null, null);
        assertThat(over.status()).isEqualTo(400);
        assertThat(over.body().path("message").asText()).contains("excede el saldo");
        assertThat(pay(s, s.token(), id, "TRANSFER", "100.00", null, null).status()).as("transferencia sin referencia").isEqualTo(400);
        assertThat(pay(s, s.token(), id, "CARD", "600.00", "AUT-55821", null).status()).isEqualTo(201);
        Res nothingDue = pay(s, s.token(), id, "CASH", "1.00", null, null);
        assertThat(nothingDue.body().path("message").asText()).contains("no tiene saldo");

        JsonNode cash = get("/api/admin/cash/current", s.token(), s.slug()).data();
        assertThat(cash.path("summary").path("cashIn").decimalValue()).isEqualByComparingTo("400");
        assertThat(cash.path("summary").path("cardIn").decimalValue()).isEqualByComparingTo("600");
        assertThat(cash.path("summary").path("expectedCash").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    void retryingWithTheSameIdempotencyKeyDoesNotChargeTwice() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        openCash(s, s.token(), "0");
        assertThat(pay(s, s.token(), id, "CASH", "300.00", null, "key-abc-1").status()).isEqualTo(201);
        Res again = pay(s, s.token(), id, "CASH", "300.00", null, "key-abc-1");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.data().path("account").path("paid").decimalValue()).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE reservation_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(pay(s, s.token(), id, "CASH", "500.00", null, "key-abc-1").status()).isEqualTo(409);
    }

    @Test
    void paymentsAndCashMovementsCannotBeDeletedOrAlteredInTheDatabase() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        openCash(s, s.token(), "0");
        pay(s, s.token(), id, "CASH", "250.00", null, null);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE reservation_id = ?", id))
                .rootCause().hasMessageContaining("Los pagos no se eliminan");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET amount = 1 WHERE reservation_id = ?", id))
                .rootCause().hasMessageContaining("no cambian");
        assertThatThrownBy(() -> jdbc.update("UPDATE cash_movements SET amount = 1 WHERE hotel_id = ?", s.hotel().id()))
                .rootCause().hasMessageContaining("solo inserción");
    }

    @Test
    void reversalCorrectsACaptureErrorWithoutTouchingTheOriginal() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        String desk = receptionist(s);
        openCash(s, desk, "1000.00");
        JsonNode acc = pay(s, desk, id, "CASH", "500.00", null, null).data().path("account");
        long paymentId = acc.path("payments").get(0).path("id").asLong();

        String path = "/api/admin/reservations/" + id + "/payments/" + paymentId + "/reverse";
        assertThat(post(s, desk, path, Map.of("reason", "Error")).status()).as("recepción no reversa").isEqualTo(403);
        // El efectivo sale de la caja de quien reversa: el admin necesita la suya.
        assertThat(post(s, s.token(), path, Map.of("reason", "Se capturó dos veces")).body().path("message").asText()).contains("Abre tu caja");
        openCash(s, s.token(), "600.00");
        Res reversed = post(s, s.token(), path, Map.of("reason", "Se capturó dos veces"));
        assertThat(reversed.status()).as(reversed.body().toString()).isEqualTo(200);
        assertThat(reversed.data().path("account").path("paid").decimalValue()).isEqualByComparingTo("0");
        assertThat(reversed.data().path("account").path("payments").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId)).isEqualTo("COMPLETED");
        assertThat(post(s, s.token(), path, Map.of("reason", "Otra vez")).status()).isEqualTo(400);
    }

    @Test
    void refundsFollowThePolicyUnlessAuthorized() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        openCash(s, s.token(), "0");
        pay(s, s.token(), id, "CASH", "600.00", null, null);
        String cashier = userWith(s, "PAYMENTS:VIEW", "PAYMENTS:REFUND", "CASH:OPERATE", "RESERVATIONS:VIEW");
        openCash(s, cashier, "1000.00");
        String refunds = "/api/admin/reservations/" + id + "/refunds";

        Res outOfPolicy = post(s, cashier, refunds, Map.of("method", "CASH", "amount", "100.00", "reason", "Cortesía"));
        assertThat(outOfPolicy.status()).as("sin saldo a favor el reembolso es extraordinario").isEqualTo(403);

        // Cancelación sin costo: todo lo cobrado queda a favor del huésped y se puede devolver dentro de la política.
        assertThat(post(s, s.token(), "/api/admin/reservations/" + id + "/cancel", Map.of("reason", "Cambio de planes")).status()).isEqualTo(200);
        assertThat(account(s, id).path("balance").decimalValue()).isEqualByComparingTo("-600");
        assertThat(post(s, cashier, refunds, Map.of("method", "CASH", "amount", "700.00", "reason", "x")).status())
                .as("nunca más de lo cobrado").isEqualTo(400);
        Res refunded = post(s, cashier, refunds, Map.of("method", "CASH", "amount", "600.00", "reason", "Cancelación sin costo"));
        assertThat(refunded.status()).as(refunded.body().toString()).isEqualTo(200);
        assertThat(refunded.data().path("account").path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(get("/api/admin/cash/current", cashier, s.slug()).data().path("summary").path("cashOut").decimalValue())
                .isEqualByComparingTo("600");
    }

    @Test
    void closingTheCashComputesTheDifferenceAndLocksIt() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        long sessionId = openCash(s, s.token(), "1000.00").data().path("id").asLong();
        pay(s, s.token(), id, "CASH", "300.00", null, null);
        assertThat(post(s, s.token(), "/api/admin/cash/" + sessionId + "/adjust",
                Map.of("type", "WITHDRAWAL", "amount", "200.00", "reason", "Envío a bóveda")).status()).isEqualTo(200);
        String close = "/api/admin/cash/" + sessionId + "/close";
        assertThat(post(s, s.token(), close, Map.of("countedCash", "1050.00")).body().path("message").asText())
                .as("una diferencia exige explicación").contains("diferencia");
        Res closed = post(s, s.token(), close, Map.of("countedCash", "1050.00", "notes", "Faltante de cambio"));
        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.data().path("expectedCash").decimalValue()).isEqualByComparingTo("1100");
        assertThat(closed.data().path("difference").decimalValue()).isEqualByComparingTo("-50");

        assertThat(post(s, s.token(), "/api/admin/cash/" + sessionId + "/adjust",
                Map.of("type", "DEPOSIT", "amount", "50.00", "reason", "x")).status()).isEqualTo(400);
        assertThat(pay(s, s.token(), id, "CASH", "10.00", null, null).body().path("message").asText()).contains("Abre tu caja");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cash_movements (hotel_id, session_id, type, method, direction, amount, reason, user_id)
                SELECT hotel_id, id, 'DEPOSIT', 'CASH', 'IN', 1, 'x', user_id FROM cash_sessions WHERE id = ?""", sessionId))
                .rootCause().hasMessageContaining("no está abierta");
    }

    @Test
    void chargesAddToTheAccountAndAreVoidedNotDeleted() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        Res added = post(s, s.token(), "/api/admin/reservations/" + id + "/charges",
                Map.of("description", "Minibar", "quantity", "2", "unitPrice", "75.50"));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.data().path("account").path("amountDue").decimalValue()).isEqualByComparingTo("1151");
        long chargeId = added.data().path("account").path("charges").get(0).path("id").asLong();
        String desk = receptionist(s);
        assertThat(post(s, desk, "/api/admin/reservations/" + id + "/charges/" + chargeId + "/void", Map.of("reason", "x")).status())
                .isEqualTo(403);
        Res voided = post(s, s.token(), "/api/admin/reservations/" + id + "/charges/" + chargeId + "/void", Map.of("reason", "Cargo duplicado"));
        assertThat(voided.data().path("account").path("amountDue").decimalValue()).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation_charges WHERE reservation_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void paymentsReportSeparatesMethodsAndNetsRefunds() {
        Setup s = setup(1);
        long id = id(booking(s, FUTURE, FUTURE.plusDays(1), true));
        openCash(s, s.token(), "0");
        pay(s, s.token(), id, "CASH", "300.00", null, null);
        pay(s, s.token(), id, "TRANSFER", "200.00", "SPEI-123", null);
        JsonNode report = get("/api/admin/payments?from=" + today() + "&to=" + today(), s.token(), s.slug()).data();
        assertThat(report.path("totalElements").asLong()).isEqualTo(2);
        assertThat(report.path("net").decimalValue()).isEqualByComparingTo("500");
        assertThat(report.path("totals").size()).isEqualTo(2);
    }

    // ── Pago en línea (Openpay) ────────────────────────────────────────────────────────

    private record Online(Setup s, String folio, String token, String webhookAuth) {
    }

    /** Hotel con Openpay (simulador) activo y política de 50 % de anticipo; reserva desde el portal. */
    private Online onlineHotel() {
        Setup s = setup(1);
        Res settings = call(HttpMethod.PUT, "/api/admin/payment-settings", Map.of("cashEnabled", true, "cardEnabled", true,
                "transferEnabled", true, "onlineEnabled", true, "sandbox", true, "merchantId", OpenpayStub.MERCHANT,
                "privateKey", OpenpayStub.PRIVATE_KEY), s.token(), s.slug(), null);
        assertThat(settings.status()).as(settings.body().toString()).isEqualTo(200);
        assertThat(settings.data().toString()).doesNotContain(OpenpayStub.PRIVATE_KEY);
        assertThat(post(s, s.token(), "/api/admin/payment-settings/test", Map.of()).status()).isEqualTo(200);
        JsonNode hook = post(s, s.token(), "/api/admin/payment-settings/webhook", Map.of()).data();
        String auth = "Basic " + Base64.getEncoder().encodeToString((hook.path("user").asText() + ":" + hook.path("password").asText()).getBytes());
        jdbc.update("INSERT INTO hotel_policies (hotel_id, version, deposit_percent) VALUES (?, 2, 50)", s.hotel().id());

        Map<String, Object> body = new HashMap<>(Map.of("roomTypeId", s.typeId(), "checkIn", FUTURE.toString(),
                "checkOut", FUTURE.plusDays(1).toString(), "adults", 2, "children", 0, "guest", guest("online@mail.mx"),
                "acceptTerms", true, "acceptPrivacy", true));
        Res created = call(HttpMethod.POST, "/api/public/reservations", body, null, s.slug(), null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(created.data().path("paymentRequired").asBoolean()).isTrue();
        assertThat(created.data().path("amountDue").decimalValue()).isEqualByComparingTo("500");
        return new Online(s, created.data().path("folio").asText(), created.data().path("accessToken").asText(), auth);
    }

    private JsonNode startGuestPayment(Online o) {
        Res res = raw(HttpMethod.POST, "/api/public/reservations/" + o.folio() + "/payments", Map.of("purpose", "DEPOSIT"),
                Map.of("X-Hotel", o.s().slug(), "X-Reservation-Token", o.token()));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data();
    }

    private String chargeIdFor(Online o) {
        return jdbc.queryForObject("""
                SELECT p.provider_charge_id FROM payments p JOIN reservations r ON r.id = p.reservation_id
                WHERE r.folio = ? AND p.status = 'PENDING'""", String.class, o.folio());
    }

    private Res webhook(Online o, String auth, String chargeId, String claimedStatus) {
        Map<String, Object> event = Map.of("type", "charge.succeeded", "event_date", "2026-10-08T10:00:00-06:00",
                "transaction", Map.of("id", chargeId, "status", claimedStatus, "amount", 500));
        Map<String, String> headers = auth == null ? Map.of() : Map.of("Authorization", auth);
        return raw(HttpMethod.POST, "/api/public/webhooks/openpay/" + o.s().slug(), event, headers);
    }

    private String reservationStatus(Online o) {
        return jdbc.queryForObject("SELECT status FROM reservations WHERE folio = ? AND hotel_id = ?", String.class,
                o.folio(), o.s().hotel().id());
    }

    @Test
    void onlinePaymentIsConfirmedOnlyByAskingTheGateway() {
        Online o = onlineHotel();
        JsonNode start = startGuestPayment(o);
        assertThat(start.path("url").asText()).contains("/pay/trstub");
        assertThat(start.path("amount").decimalValue()).isEqualByComparingTo("500");
        String chargeId = chargeIdFor(o);

        assertThat(webhook(o, null, chargeId, "completed").status()).isEqualTo(401);
        assertThat(webhook(o, "Basic " + Base64.getEncoder().encodeToString("x:y".getBytes()), chargeId, "completed").status()).isEqualTo(401);
        // Autenticado, pero el cuerpo miente: la pasarela dice que sigue pendiente.
        assertThat(webhook(o, o.webhookAuth(), chargeId, "completed").status()).isEqualTo(200);
        assertThat(reservationStatus(o)).isEqualTo("PENDING_PAYMENT");

        OpenpayStub.complete(chargeId);
        assertThat(webhook(o, o.webhookAuth(), chargeId, "completed").status()).isEqualTo(200);
        assertThat(webhook(o, o.webhookAuth(), chargeId, "completed").status()).as("duplicado").isEqualTo(200);
        assertThat(reservationStatus(o)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT paid_total FROM reservations WHERE folio = ? AND hotel_id = ?",
                java.math.BigDecimal.class, o.folio(), o.s().hotel().id())).isEqualByComparingTo("500");
        assertThat(jdbc.queryForObject("SELECT card_last4 FROM payments WHERE provider_charge_id = ?", String.class, chargeId))
                .isEqualTo("1111");

        JsonNode status = raw(HttpMethod.GET, "/api/public/payments/" + start.path("reference").asText(), null,
                Map.of("X-Hotel", o.s().slug())).data();
        assertThat(status.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("reservationStatus").asText()).isEqualTo("CONFIRMED");
    }

    @Test
    void theReturnPageVerifiesWithTheGatewayAndRefundsGoBackThroughIt() {
        Online o = onlineHotel();
        JsonNode start = startGuestPayment(o);
        String chargeId = chargeIdFor(o);
        String ref = start.path("reference").asText();
        Map<String, String> pub = Map.of("X-Hotel", o.s().slug());
        assertThat(raw(HttpMethod.GET, "/api/public/payments/" + ref, null, pub).data().path("status").asText()).isEqualTo("PENDING");
        OpenpayStub.complete(chargeId);
        assertThat(raw(HttpMethod.GET, "/api/public/payments/" + ref, null, pub).data().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(reservationStatus(o)).isEqualTo("CONFIRMED");

        long reservationId = jdbc.queryForObject("SELECT id FROM reservations WHERE folio = ? AND hotel_id = ?", Long.class,
                o.folio(), o.s().hotel().id());
        long paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE provider_charge_id = ?", Long.class, chargeId);
        Res refund = post(o.s(), o.s().token(), "/api/admin/reservations/" + reservationId + "/refunds",
                Map.of("method", "ONLINE", "amount", "200.00", "relatedPaymentId", paymentId, "reason", "Ajuste autorizado"));
        assertThat(refund.status()).as(refund.body().toString()).isEqualTo(200);
        assertThat(refund.data().path("account").path("paid").decimalValue()).isEqualByComparingTo("300");
        assertThat(OpenpayStub.charge(chargeId).path("refund").path("amount").decimalValue()).isEqualByComparingTo("200");
    }

    @Test
    void aChargeWhoseAmountDoesNotMatchIsNeverAccepted() {
        Online o = onlineHotel();
        JsonNode start = startGuestPayment(o);
        String chargeId = chargeIdFor(o);
        OpenpayStub.tamperAmount(chargeId, "1.00");
        OpenpayStub.complete(chargeId);
        JsonNode status = raw(HttpMethod.GET, "/api/public/payments/" + start.path("reference").asText(), null,
                Map.of("X-Hotel", o.s().slug())).data();
        assertThat(status.path("status").asText()).isEqualTo("FAILED");
        assertThat(reservationStatus(o)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'PAYMENT_MISMATCH' AND hotel_id = ?",
                Integer.class, o.s().hotel().id())).isEqualTo(1);
    }

    @Test
    void whenTheGatewayIsDownThePaymentStaysPendingAndTheWebhookAsksToRetry() {
        Online o = onlineHotel();
        startGuestPayment(o);
        String chargeId = chargeIdFor(o);
        OpenpayStub.complete(chargeId);
        OpenpayStub.setDown(true);
        try {
            assertThat(webhook(o, o.webhookAuth(), chargeId, "completed").status()).isEqualTo(503);
            assertThat(reservationStatus(o)).isEqualTo("PENDING_PAYMENT");
        } finally {
            OpenpayStub.setDown(false);
        }
        assertThat(webhook(o, o.webhookAuth(), chargeId, "completed").status()).isEqualTo(200);
        assertThat(reservationStatus(o)).isEqualTo("CONFIRMED");
    }

    @Test
    void onlinePaymentNeedsCredentialsAndTheKeyIsNeverReturned() {
        Setup s = setup(1);
        Res noKey = call(HttpMethod.PUT, "/api/admin/payment-settings", Map.of("cashEnabled", true, "cardEnabled", true,
                "transferEnabled", true, "onlineEnabled", true, "sandbox", true, "merchantId", OpenpayStub.MERCHANT), s.token(), s.slug(), null);
        assertThat(noKey.status()).isEqualTo(400);
        call(HttpMethod.PUT, "/api/admin/payment-settings", Map.of("cashEnabled", true, "cardEnabled", true,
                "transferEnabled", true, "onlineEnabled", false, "sandbox", true, "merchantId", OpenpayStub.MERCHANT,
                "privateKey", "sk_wrongkey1234567890"), s.token(), s.slug(), null);
        Res test = post(s, s.token(), "/api/admin/payment-settings/test", Map.of());
        assertThat(test.status()).isEqualTo(400);
        assertThat(test.body().path("message").asText()).contains("credenciales");
        JsonNode got = get("/api/admin/payment-settings", s.token(), s.slug()).data();
        assertThat(got.path("privateKeySet").asBoolean()).isTrue();
        assertThat(got.toString()).doesNotContain("sk_wrongkey");
        assertThat(jdbc.queryForObject("SELECT private_key_enc FROM payment_settings WHERE hotel_id = ?", String.class, s.hotel().id()))
                .doesNotContain("sk_wrongkey");
        assertThat(get("/api/admin/payment-settings", receptionist(s), s.slug()).status()).isEqualTo(403);
    }
}
