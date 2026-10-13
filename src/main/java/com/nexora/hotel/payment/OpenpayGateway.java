package com.nexora.hotel.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexora.hotel.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * Cliente REST de Openpay México (API v1). Autenticación HTTP Basic con la llave PRIVADA del comercio (solo en el
 * servidor). Los cargos usan la página de pago de Openpay ("cargo con redirección"): este sistema nunca ve ni guarda
 * el número de tarjeta ni el CVV.
 * <p>
 * Estados de Openpay → normalizados: {@code completed} → COMPLETED; {@code in_progress}/{@code charge_pending} →
 * PENDING; {@code failed} → FAILED; {@code cancelled}/{@code expired} → CANCELLED; {@code refunded} → REFUNDED.
 */
@Slf4j
@Component
public class OpenpayGateway implements PaymentGateway {

    public static final String PROVIDER = "OPENPAY";

    private final ObjectMapper mapper;
    private final AppProperties.Payments config;
    private final HttpClient http;

    public OpenpayGateway(ObjectMapper mapper, AppProperties props) {
        this.mapper = mapper;
        this.config = props.payments();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Charge createRedirectCharge(Credentials c, ChargeRequest r) {
        ObjectNode body = mapper.createObjectNode();
        body.put("method", "card");
        body.put("amount", r.amount().setScale(2, RoundingMode.HALF_UP));
        body.put("currency", r.currency());
        body.put("description", truncate(r.description(), 250));
        body.put("order_id", r.orderId());
        body.put("send_email", false);
        body.put("confirm", "false");
        body.put("redirect_url", r.redirectUrl());
        ObjectNode customer = body.putObject("customer");
        customer.put("name", truncate(r.customer().name(), 100));
        customer.put("last_name", truncate(r.customer().lastName(), 100));
        if (r.customer().email() != null) {
            customer.put("email", r.customer().email());
        }
        if (r.customer().phone() != null && r.customer().phone().matches("\\d{10}")) {
            customer.put("phone_number", r.customer().phone());
        }
        return toCharge(send(c, "POST", "/charges", body));
    }

    @Override
    public Charge getCharge(Credentials c, String chargeId) {
        return toCharge(send(c, "GET", "/charges/" + encode(chargeId), null));
    }

    @Override
    public Charge refund(Credentials c, String chargeId, BigDecimal amount, String description) {
        ObjectNode body = mapper.createObjectNode();
        body.put("description", truncate(description, 250));
        body.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
        return toCharge(send(c, "POST", "/charges/" + encode(chargeId) + "/refund", body));
    }

    @Override
    public void ping(Credentials c) {
        send(c, "GET", "", null);
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────────────

    private JsonNode send(Credentials c, String method, String path, JsonNode body) {
        if (c.merchantId() == null || !c.merchantId().matches("[A-Za-z0-9]{6,40}")) {
            throw new GatewayException("El ID de comercio de Openpay no es válido", false, null, null);
        }
        String base = (c.sandbox() ? config.openpaySandboxUrl() : config.openpayProductionUrl()).replaceAll("/+$", "");
        String auth = Base64.getEncoder().encodeToString((c.privateKey() + ":").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v1/" + c.merchantId() + path))
                .timeout(config.timeout())
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/json");
        try {
            if (body == null) {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = res.body() == null || res.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(res.body());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return json;
            }
            throw new GatewayException(describeError(res.statusCode(), json), res.statusCode() >= 500, res.statusCode(), null);
        } catch (IOException e) {
            throw new GatewayException("No hubo respuesta de Openpay. Intenta de nuevo en unos minutos.", true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("Se interrumpió la comunicación con Openpay", true, null, e);
        }
    }

    /** Mensajes en español para los errores más comunes; el código original queda entre paréntesis. */
    static String describeError(int status, JsonNode json) {
        int code = json.path("error_code").asInt(0);
        String reason = switch (code) {
            case 1001 -> "La solicitud enviada a Openpay no es válida";
            case 1002, 1003 -> "Openpay rechazó las credenciales: revisa el ID de comercio y la llave privada";
            case 1004 -> "Openpay no está disponible en este momento";
            case 1005 -> "El cargo no existe en Openpay";
            case 1006 -> "Ya existe un cargo con ese número de orden";
            case 1009 -> "La operación excede el límite permitido";
            case 3001 -> "La tarjeta fue declinada";
            case 3002 -> "La tarjeta está vencida";
            case 3003 -> "La tarjeta no tiene fondos suficientes";
            case 3004 -> "La tarjeta fue reportada como robada";
            case 3005 -> "La tarjeta fue rechazada por el sistema antifraude";
            case 3008, 3009 -> "La tarjeta no admite este tipo de transacción";
            default -> status == 401 || status == 403 ? "Openpay rechazó las credenciales" : "Openpay no aceptó la operación";
        };
        return code == 0 ? reason + " (HTTP " + status + ")" : reason + " (código " + code + ")";
    }

    private Charge toCharge(JsonNode j) {
        String raw = j.path("status").asText("");
        State state = switch (raw.toLowerCase(Locale.ROOT)) {
            case "completed" -> State.COMPLETED;
            case "failed" -> State.FAILED;
            case "cancelled", "expired" -> State.CANCELLED;
            case "refunded" -> State.REFUNDED;
            default -> State.PENDING;
        };
        String url = j.path("payment_method").path("url").asText(null);
        String number = j.path("card").path("card_number").asText("");
        String last4 = number.length() >= 4 && number.substring(number.length() - 4).matches("\\d{4}")
                ? number.substring(number.length() - 4) : null;
        String brand = j.path("card").path("brand").asText(null);
        BigDecimal refunded = j.path("refund").has("amount") ? new BigDecimal(j.path("refund").path("amount").asText("0")) : null;
        return new Charge(j.path("id").asText(null), j.path("order_id").asText(null), raw, state,
                j.has("amount") ? new BigDecimal(j.path("amount").asText("0")) : null, j.path("currency").asText(null),
                url, j.path("error_message").asText(null), brand, last4, refunded);
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String truncate(String v, int max) {
        return v == null ? "" : v.length() <= max ? v : v.substring(0, max);
    }
}
