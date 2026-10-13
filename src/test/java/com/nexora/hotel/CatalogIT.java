package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 5: inventario, imágenes persistentes, bloqueos sin traslape, configuración y tarifas. */
class CatalogIT extends SecurityTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.storage.dir}")
    private String storageDir;

    private static final LocalDate BASE = LocalDate.now().plusDays(30);

    private Map<String, Object> roomType(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Doble " + code);
        body.put("description", "Dos camas matrimoniales");
        body.put("basePrice", "1500.00");
        body.put("maxAdults", 3);
        body.put("maxChildren", 2);
        body.put("maxOccupancy", 4);
        body.put("includedOccupancy", 2);
        body.put("extraAdultFee", "350.00");
        body.put("extraChildFee", "200.00");
        body.put("bedCount", 2);
        body.put("bedType", "MATRIMONIAL");
        body.put("sortOrder", 0);
        return body;
    }

    private long createType(TestHotel hotel, String token, String code) {
        Res res = call(HttpMethod.POST, "/api/admin/room-types", roomType(code), token, hotel.slug(), null);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(201);
        return res.data().path("id").asLong();
    }

    private long createRoom(TestHotel hotel, String token, long typeId, String number) {
        Res res = call(HttpMethod.POST, "/api/admin/rooms",
                Map.of("roomTypeId", typeId, "number", number, "floor", "1", "sortOrder", 0), token, hotel.slug(), null);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(201);
        return res.data().path("id").asLong();
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, 0x0d9488);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private ResponseEntity<String> upload(String path, String part, String filename, byte[] content, String token, String slug) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        headers.set("X-Hotel", slug);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_JPEG);
        body.add(part, new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, fileHeaders));
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void roomTypesAndRoomsWithValidationAndUniqueness() {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        long typeId = createType(hotel, token, "DBL");
        createRoom(hotel, token, typeId, "101");

        assertThat(call(HttpMethod.POST, "/api/admin/rooms", Map.of("roomTypeId", typeId, "number", "101", "sortOrder", 0),
                token, hotel.slug(), null).status()).isEqualTo(409);

        Map<String, Object> invalid = roomType("BAD");
        invalid.put("maxOccupancy", 9);
        invalid.put("basePrice", "-5");
        Res bad = call(HttpMethod.POST, "/api/admin/room-types", invalid, token, hotel.slug(), null);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("errors").has("basePrice")).isTrue();

        assertThat(call(HttpMethod.PATCH, "/api/admin/room-types/" + typeId + "/active", Map.of("active", false),
                token, hotel.slug(), null).status()).as("tiene habitaciones activas").isEqualTo(409);
    }

    @Test
    void imagesAreSanitizedPersistedOutsideTheBuildAndServed() throws Exception {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        long typeId = createType(hotel, token, "STE");

        ResponseEntity<String> ok = upload("/api/admin/room-types/" + typeId + "/images", "files", "../../etc/passwd.png",
                png(40, 30), token, hotel.slug());
        assertThat(ok.getStatusCode().value()).as(ok.getBody()).isEqualTo(200);
        JsonNode image = objectMapper.readTree(ok.getBody()).path("data").get(0);
        String imageUrl = image.path("url").asText();
        assertThat(imageUrl).contains("/media/hotels/" + hotel.id() + "/room-types/").doesNotContain("passwd");
        assertThat(image.path("width").asInt()).isEqualTo(40);

        String key = imageUrl.substring(imageUrl.indexOf("/media/") + 7);
        Path onDisk = Path.of(storageDir).toAbsolutePath().normalize().resolve(key);
        assertThat(Files.exists(onDisk)).isTrue();
        assertThat(onDisk.toString()).doesNotContain("classes");

        ResponseEntity<byte[]> served = restTemplate.getForEntity(url("/media/" + key), byte[].class);
        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(served.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        ResponseEntity<String> fake = upload("/api/admin/room-types/" + typeId + "/images", "files", "foto.jpg",
                "<?php system($_GET['c']); ?>".getBytes(), token, hotel.slug());
        assertThat(fake.getStatusCode().value()).isEqualTo(400);

        // El firewall de Spring Security rechaza ".." (400); si llegara al controlador, la clave no es válida (404).
        assertThat(restTemplate.getForEntity(url("/media/hotels/1/rooms/../../../../etc/passwd"), String.class)
                .getStatusCode().value()).isIn(400, 404);

        Res deleted = call(HttpMethod.DELETE, "/api/admin/room-types/" + typeId + "/images/" + image.path("id").asLong(),
                null, token, hotel.slug(), null);
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(Files.exists(onDisk)).isFalse();
        assertThat(restTemplate.getForEntity(url("/media/" + key), byte[].class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void maintenanceBlocksNeverOverlapButCanTouch() {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        long roomId = createRoom(hotel, token, createType(hotel, token, "SGL"), "201");
        String path = "/api/admin/rooms/" + roomId + "/blocks";

        assertThat(call(HttpMethod.POST, path, block(BASE, BASE.plusDays(3)), token, hotel.slug(), null).status()).isEqualTo(201);
        Res overlap = call(HttpMethod.POST, path, block(BASE.plusDays(2), BASE.plusDays(5)), token, hotel.slug(), null);
        assertThat(overlap.status()).isEqualTo(409);
        // La salida no es noche ocupada: un bloqueo que empieza el día que termina el otro sí cabe.
        assertThat(call(HttpMethod.POST, path, block(BASE.plusDays(3), BASE.plusDays(4)), token, hotel.slug(), null).status()).isEqualTo(201);
        assertThat(call(HttpMethod.POST, path, block(BASE.plusDays(5), BASE.plusDays(5)), token, hotel.slug(), null).status()).isEqualTo(400);
    }

    @Test
    void concurrentBlocksOnTheSameDatesOnlyOneWins() throws Exception {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        long roomId = createRoom(hotel, token, createType(hotel, token, "KNG"), "301");
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return call(HttpMethod.POST, "/api/admin/rooms/" + roomId + "/blocks",
                        block(BASE.plusDays(10), BASE.plusDays(12)), token, hotel.slug(), null).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = attempts.stream().map(CompletableFuture::join).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(5);
        Integer occupancies = jdbc.queryForObject("SELECT count(*) FROM room_occupancies WHERE room_id = ?", Integer.class, roomId);
        assertThat(occupancies).isEqualTo(1);
    }

    @Test
    void receptionCanCleanButNotManageRooms() {
        TestHotel hotel = newHotel();
        String admin = token(hotel.adminEmail(), hotel.slug());
        long roomId = createRoom(hotel, admin, createType(hotel, admin, "TWN"), "102");
        User receptionist = createUser(hotel, Role.RECEPTIONIST);
        String reception = token(receptionist.getEmail(), hotel.slug());

        assertThat(get("/api/admin/rooms", reception, hotel.slug()).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/admin/rooms", Map.of("roomTypeId", 1, "number", "999", "sortOrder", 0),
                reception, hotel.slug(), null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "CLEANING"),
                reception, hotel.slug(), null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "MAINTENANCE"),
                reception, hotel.slug(), null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.PATCH, "/api/admin/rooms/" + roomId + "/status", Map.of("status", "OCCUPIED"),
                admin, hotel.slug(), null).status()).as("solo el check-in marca ocupada").isEqualTo(400);
        assertThat(get("/api/admin/rooms/" + roomId + "/status-history", reception, hotel.slug()).data().size()).isEqualTo(1);
    }

    @Test
    void roomsOfAnotherHotelAreInvisible() {
        TestHotel a = newHotel();
        TestHotel b = newHotel();
        String tokenA = token(a.adminEmail(), a.slug());
        long roomA = createRoom(a, tokenA, createType(a, tokenA, "DBL"), "101");
        String tokenB = token(b.adminEmail(), b.slug());

        assertThat(get("/api/admin/rooms", tokenB, a.slug()).data().size()).isZero();
        assertThat(call(HttpMethod.PUT, "/api/admin/rooms/" + roomA, Map.of("roomTypeId", 1, "number", "X1", "sortOrder", 0),
                tokenB, a.slug(), null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/admin/rooms/" + roomA + "/blocks", block(BASE, BASE.plusDays(1)),
                tokenB, b.slug(), null).status()).isEqualTo(404);
    }

    @Test
    void themeRequiresReadableContrastAndIsPublic() {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        assertThat(call(HttpMethod.PUT, "/api/admin/settings/theme",
                Map.of("primary", "#f5f5f5", "accent", "#c8a24a", "sidebar", "#0a0f1c", "defaultMode", "light"),
                token, hotel.slug(), null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/api/admin/settings/theme",
                Map.of("primary", "url(javascript:alert(1))", "accent", "#c8a24a", "sidebar", "#0a0f1c", "defaultMode", "light"),
                token, hotel.slug(), null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/api/admin/settings/theme",
                Map.of("primary", "#1D4ED8", "accent", "#c8a24a", "sidebar", "#111827", "defaultMode", "dark"),
                token, hotel.slug(), null).status()).isEqualTo(200);

        JsonNode publicHotel = get("/api/public/hotel", null, hotel.slug()).data();
        assertThat(publicHotel.path("theme").path("primary").asText()).isEqualTo("#1d4ed8");
        assertThat(publicHotel.has("legalName")).isFalse();
    }

    @Test
    void policiesAreVersionedAndOldVersionsImmutable() {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        JsonNode current = get("/api/admin/settings/policies", token, hotel.slug()).data();
        assertThat(current.path("version").asInt()).isEqualTo(1);

        Map<String, Object> values = objectMapper.convertValue(current.path("values"), Map.class);
        values.put("freeCancelHours", 72);
        Res saved = call(HttpMethod.PUT, "/api/admin/settings/policies", values, token, hotel.slug(), null);
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        assertThat(saved.data().path("version").asInt()).isEqualTo(2);

        assertThatThrownBy(() -> jdbc.update("UPDATE hotel_policies SET free_cancel_hours = 0 WHERE hotel_id = ?", hotel.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void quoteUsesRulesAndTaxes() {
        TestHotel hotel = newHotel();
        String token = token(hotel.adminEmail(), hotel.slug());
        long typeId = createType(hotel, token, "DBL");
        createRoom(hotel, token, typeId, "101");
        LocalDate friday = BASE.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.FRIDAY));
        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "Fin de semana");
        rule.put("ruleType", "WEEKEND");
        rule.put("daysOfWeek", List.of("FRIDAY", "SATURDAY"));
        rule.put("adjustmentType", "PERCENT");
        rule.put("adjustmentValue", "20");
        rule.put("priority", 0);
        rule.put("active", true);
        assertThat(call(HttpMethod.POST, "/api/admin/rate-rules", rule, token, hotel.slug(), null).status()).isEqualTo(201);

        Res quote = call(HttpMethod.POST, "/api/admin/rates/quote", Map.of("roomTypeId", typeId,
                "checkIn", friday.minusDays(1).toString(), "checkOut", friday.plusDays(1).toString(), "adults", 2, "children", 0),
                token, hotel.slug(), null);
        assertThat(quote.status()).as(quote.body().toString()).isEqualTo(200);
        // 1500 + 1800 = 3300; IVA 16 % (528) + ISH 3 % (99) = 3927
        assertThat(quote.data().path("subtotal").decimalValue()).isEqualByComparingTo("3300.00");
        assertThat(quote.data().path("total").decimalValue()).isEqualByComparingTo("3927.00");

        assertThat(call(HttpMethod.POST, "/api/admin/rates/quote", Map.of("roomTypeId", typeId,
                "checkIn", friday.toString(), "checkOut", friday.plusDays(1).toString(), "adults", 5, "children", 0),
                token, hotel.slug(), null).status()).as("excede capacidad").isEqualTo(400);

        JsonNode publicTypes = get("/api/public/room-types", null, hotel.slug()).data();
        assertThat(publicTypes.size()).isEqualTo(1);
        assertThat(publicTypes.get(0).has("internalNotes")).isFalse();
    }

    private static Map<String, Object> block(LocalDate from, LocalDate to) {
        return Map.of("startsOn", from.toString(), "endsOn", to.toString(), "reason", "Cambio de alfombra");
    }
}
