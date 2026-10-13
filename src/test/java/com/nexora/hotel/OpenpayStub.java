package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulador local de la API de Openpay (solo pruebas). Replica los endpoints que usa {@code OpenpayGateway}
 * (crear cargo con redirección, consultar, reembolsar y consultar comercio) con su autenticación HTTP Basic y su
 * formato de errores. Las pruebas deciden el resultado de cada cargo con {@link #complete}, {@link #fail}, etc.
 * <p>
 * No es un "pago simulado" del sistema: el código de producción habla HTTP real con esto igual que con Openpay.
 */
public final class OpenpayStub {

    public static final String MERCHANT = "mtestmerchant01";
    public static final String PRIVATE_KEY = "sk_test1234567890abcdef";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PATH = Pattern.compile("^/v1/([A-Za-z0-9]+)(/charges(?:/([A-Za-z0-9]+))?(/refund)?)?$");
    private static final Map<String, ObjectNode> CHARGES = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile boolean down;
    private static HttpServer server;

    private OpenpayStub() {
    }

    public static synchronized String start() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", OpenpayStub::handle);
            server.start();
        }
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public static void complete(String chargeId) {
        ObjectNode c = CHARGES.get(chargeId);
        c.put("status", "completed");
        ObjectNode card = c.putObject("card");
        card.put("brand", "visa");
        card.put("card_number", "411111XXXXXX1111");
    }

    public static void fail(String chargeId, String message) {
        ObjectNode c = CHARGES.get(chargeId);
        c.put("status", "failed");
        c.put("error_message", message);
    }

    /** Altera el importe que reporta la pasarela (para probar que no se acepta un cargo que no coincide). */
    public static void tamperAmount(String chargeId, String amount) {
        CHARGES.get(chargeId).put("amount", new BigDecimal(amount));
    }

    public static void setDown(boolean value) {
        down = value;
    }

    public static ObjectNode charge(String chargeId) {
        return CHARGES.get(chargeId);
    }

    /** Busca el cargo creado con ese número de orden. */
    public static String chargeForOrder(String orderId) {
        return CHARGES.values().stream().filter(c -> orderId.equals(c.path("order_id").asText()))
                .map(c -> c.path("id").asText()).findFirst().orElseThrow();
    }

    public static int requests() {
        return REQUESTS.get();
    }

    private static void handle(HttpExchange ex) throws IOException {
        REQUESTS.incrementAndGet();
        try (ex) {
            if (down) {
                send(ex, 503, error(1004, "Service unavailable", 503));
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String expected = "Basic " + Base64.getEncoder().encodeToString((PRIVATE_KEY + ":").getBytes(StandardCharsets.UTF_8));
            Matcher m = PATH.matcher(ex.getRequestURI().getPath());
            if (!m.matches() || !MERCHANT.equals(m.group(1))) {
                send(ex, 404, error(1005, "The requested resource doesn't exist", 404));
                return;
            }
            if (!expected.equals(auth)) {
                send(ex, 401, error(1002, "The api key or merchant id are invalid", 401));
                return;
            }
            String method = ex.getRequestMethod();
            String chargeId = m.group(3);
            if (m.group(2) == null) {
                ObjectNode merchant = MAPPER.createObjectNode();
                merchant.put("id", MERCHANT);
                merchant.put("name", "Comercio de pruebas");
                merchant.put("status", "active");
                send(ex, 200, merchant);
            } else if ("POST".equals(method) && chargeId == null) {
                JsonNode body = MAPPER.readTree(ex.getRequestBody());
                String orderId = body.path("order_id").asText();
                if (CHARGES.values().stream().anyMatch(c -> orderId.equals(c.path("order_id").asText()))) {
                    send(ex, 409, error(1006, "The order_id has already been processed", 409));
                    return;
                }
                String id = "trstub" + SEQ.incrementAndGet();
                ObjectNode c = MAPPER.createObjectNode();
                c.put("id", id);
                c.put("order_id", orderId);
                c.put("status", "charge_pending");
                c.put("amount", body.path("amount").decimalValue());
                c.put("currency", body.path("currency").asText("MXN"));
                c.put("description", body.path("description").asText());
                c.put("method", "card");
                ObjectNode pm = c.putObject("payment_method");
                pm.put("type", "redirect");
                pm.put("url", start() + "/pay/" + id);
                c.put("redirect_url", body.path("redirect_url").asText());
                CHARGES.put(id, c);
                send(ex, 200, c);
            } else if ("GET".equals(method) && chargeId != null && m.group(4) == null) {
                ObjectNode c = CHARGES.get(chargeId);
                if (c == null) {
                    send(ex, 404, error(1005, "The requested resource doesn't exist", 404));
                } else {
                    send(ex, 200, c);
                }
            } else if ("POST".equals(method) && chargeId != null && m.group(4) != null) {
                ObjectNode c = CHARGES.get(chargeId);
                JsonNode body = MAPPER.readTree(ex.getRequestBody());
                BigDecimal amount = body.path("amount").decimalValue();
                BigDecimal already = c.path("refund").path("amount").decimalValue();
                BigDecimal total = already.add(amount);
                if (!"completed".equals(c.path("status").asText()) && !"refunded".equals(c.path("status").asText())
                        || total.compareTo(c.path("amount").decimalValue()) > 0) {
                    send(ex, 400, error(1001, "The charge can not be refunded", 400));
                    return;
                }
                ObjectNode refund = c.putObject("refund");
                refund.put("id", "rfstub" + SEQ.incrementAndGet());
                refund.put("amount", total);
                refund.put("status", "completed");
                if (total.compareTo(c.path("amount").decimalValue()) == 0) {
                    c.put("status", "refunded");
                }
                send(ex, 200, c);
            } else {
                send(ex, 405, error(1001, "Method not allowed", 405));
            }
        } catch (RuntimeException e) {
            send(ex, 500, error(1000, e.getMessage(), 500));
        }
    }

    private static ObjectNode error(int code, String description, int http) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("category", http >= 500 ? "internal" : "request");
        e.put("description", description);
        e.put("http_code", http);
        e.put("error_code", code);
        e.put("request_id", "req-" + SEQ.incrementAndGet());
        return e;
    }

    private static void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
