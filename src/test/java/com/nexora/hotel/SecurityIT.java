package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.entity.PasswordResetToken;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.repository.PasswordResetTokenRepository;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.tenant.TenantContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Casos de seguridad obligatorios del prompt (§29). */
class SecurityIT extends SecurityTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;

    @Autowired
    private com.nexora.hotel.service.PlatformAccountService platformAccounts;

    // ── Sesión ────────────────────────────────────────────────────────────────────────

    @Test
    void withoutSessionProtectedEndpointsAre401() {
        TestHotel hotel = newHotel();
        assertThat(get("/api/admin/users", null, hotel.slug()).status()).isEqualTo(401);
        assertThat(get("/api/platform/hotels", null, null).status()).isEqualTo(401);
        assertThat(get("/api/auth/me", null, hotel.slug()).status()).isEqualTo(401);
    }

    @Test
    void invalidAndExpiredTokensAreRejected() {
        TestHotel hotel = newHotel();
        assertThat(get("/api/admin/users", "no-es-un-jwt", hotel.slug()).status()).isEqualTo(401);

        User admin = TenantContext.callAs(hotel.id(), () -> userRepository.findByEmail(hotel.adminEmail()).orElseThrow());
        String expired = Jwts.builder().setIssuer("nexora-hotel").setSubject(String.valueOf(admin.getId()))
                .claim("typ", "access")
                .setIssuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .setExpiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor("only-for-automated-tests-0123456789abcdef".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
        assertThat(get("/api/admin/users", expired, hotel.slug()).status()).isEqualTo(401);

        String forged = Jwts.builder().setIssuer("nexora-hotel").setSubject(String.valueOf(admin.getId()))
                .claim("typ", "access").setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor("otra-llave-de-atacante-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
        assertThat(get("/api/admin/users", forged, hotel.slug()).status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        TestHotel hotel = newHotel();
        Res wrongPassword = login(hotel.adminEmail(), "Incorrecta123", hotel.slug());
        Res unknownEmail = login("nadie@" + hotel.slug() + ".mx", "Incorrecta123", hotel.slug());

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.body().path("message").asText()).isEqualTo(unknownEmail.body().path("message").asText());
    }

    @Test
    void accountLocksAfterRepeatedFailures() {
        TestHotel hotel = newHotel();
        for (int i = 0; i < 5; i++) {
            login(hotel.adminEmail(), "Incorrecta123", hotel.slug());
        }
        assertThat(login(hotel.adminEmail(), PASSWORD, hotel.slug()).status()).isEqualTo(401);
    }

    @Test
    void userOfOneHotelCannotLoginThroughAnotherHotel() {
        TestHotel a = newHotel();
        TestHotel b = newHotel();
        assertThat(login(a.adminEmail(), PASSWORD, b.slug()).status()).isEqualTo(401);
        assertThat(login(a.adminEmail(), PASSWORD, null).status()).isEqualTo(401);
    }

    // ── Refresh token ─────────────────────────────────────────────────────────────────

    @Test
    void refreshRotatesAndOldCookieStopsWorking() {
        TestHotel hotel = newHotel();
        Res login = login(hotel.adminEmail(), PASSWORD, hotel.slug());
        String first = refreshCookie(login);
        assertThat(first).startsWith("nx_rt_" + hotel.slug() + "=");

        Res refreshed = call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), first);
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.data().path("accessToken").asText()).isNotBlank();
        String second = refreshCookie(refreshed);
        assertThat(second).isNotEqualTo(first);

        assertThat(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), first).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), second).status()).isEqualTo(200);
    }

    @Test
    void reusingARotatedTokenRevokesTheWholeSession() {
        TestHotel hotel = newHotel();
        String first = refreshCookie(login(hotel.adminEmail(), PASSWORD, hotel.slug()));
        String second = refreshCookie(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), first));
        // Fuera del margen de gracia: un tercero está usando una copia vieja de la cookie.
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now() - interval '5 minutes' WHERE token_hash = ?",
                SecureTokens.sha256(first.substring(first.indexOf('=') + 1)));

        assertThat(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), first).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), second).status()).isEqualTo(401);
    }

    @Test
    void refreshWithoutAjaxHeaderIsRejected() {
        TestHotel hotel = newHotel();
        String cookie = refreshCookie(login(hotel.adminEmail(), PASSWORD, hotel.slug()));
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-Hotel", hotel.slug());
        headers.add("Cookie", cookie);
        var response = restTemplate.exchange(url("/api/auth/refresh"), HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    // ── Recuperación de contraseña ────────────────────────────────────────────────────

    @Test
    void forgotPasswordAnswersTheSameForUnknownEmails() {
        TestHotel hotel = newHotel();
        Res known = call(HttpMethod.POST, "/api/auth/forgot-password", Map.of("email", hotel.adminEmail()), null, hotel.slug(), null);
        Res unknown = call(HttpMethod.POST, "/api/auth/forgot-password", Map.of("email", "x@y.mx"), null, hotel.slug(), null);
        assertThat(known.status()).isEqualTo(200);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(known.body().path("message").asText()).isEqualTo(unknown.body().path("message").asText());
    }

    @Test
    void resetTokenWorksOnceAndClosesSessions() {
        TestHotel hotel = newHotel();
        Res session = login(hotel.adminEmail(), PASSWORD, hotel.slug());
        String cookie = refreshCookie(session);
        User admin = TenantContext.callAs(hotel.id(), () -> userRepository.findByEmail(hotel.adminEmail()).orElseThrow());
        String raw = SecureTokens.randomToken();
        resetTokenRepository.save(PasswordResetToken.builder().userId(admin.getId()).tokenHash(SecureTokens.sha256(raw))
                .expiresAt(Instant.now().plusSeconds(600)).build());

        Map<String, String> body = Map.of("token", raw, "newPassword", "NuevaClave2026");
        assertThat(call(HttpMethod.POST, "/api/auth/reset-password", body, null, null, null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/auth/reset-password", body, null, null, null).status()).isEqualTo(400);

        assertThat(login(hotel.adminEmail(), "NuevaClave2026", hotel.slug()).status()).isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/auth/refresh", null, null, hotel.slug(), cookie).status()).isEqualTo(401);
    }

    @Test
    void weakPasswordIsRejectedWithFieldError() {
        Res res = call(HttpMethod.POST, "/api/auth/reset-password", Map.of("token", "abc", "newPassword", "corta"), null, null, null);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().path("errors").has("newPassword")).isTrue();
    }

    // ── Permisos dinámicos ────────────────────────────────────────────────────────────

    @Test
    void receptionistCannotManageUsersOrPermissions() {
        TestHotel hotel = newHotel();
        User receptionist = createUser(hotel, Role.RECEPTIONIST);
        String token = token(receptionist.getEmail(), hotel.slug());

        assertThat(get("/api/admin/overview", token, hotel.slug()).status()).isEqualTo(200);
        assertThat(get("/api/admin/users", token, hotel.slug()).status()).isEqualTo(403);
        assertThat(call(HttpMethod.PUT, "/api/admin/roles/" + roleId(hotel, Role.RECEPTIONIST),
                Map.of("name", "Recepcionista", "permissions", List.of("USERS:MANAGE")), token, hotel.slug(), null).status())
                .isEqualTo(403);
        assertThat(get("/api/admin/audit", token, hotel.slug()).status()).isEqualTo(403);
    }

    @Test
    void revokingAPermissionAppliesOnTheNextRequestWithTheSameToken() {
        TestHotel hotel = newHotel();
        User receptionist = createUser(hotel, Role.RECEPTIONIST);
        String receptionistToken = token(receptionist.getEmail(), hotel.slug());
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        assertThat(get("/api/admin/overview", receptionistToken, hotel.slug()).status()).isEqualTo(200);

        JsonNode role = StreamSupport.stream(get("/api/admin/roles", adminToken, hotel.slug()).data().spliterator(), false)
                .filter(r -> r.path("code").asText().equals(Role.RECEPTIONIST)).findFirst().orElseThrow();
        List<String> remaining = StreamSupport.stream(role.path("permissions").spliterator(), false)
                .map(JsonNode::asText).filter(c -> !c.equals("DASHBOARD:VIEW")).toList();
        Res saved = call(HttpMethod.PUT, "/api/admin/roles/" + role.path("id").asLong(),
                Map.of("name", "Recepcionista", "permissions", remaining), adminToken, hotel.slug(), null);
        assertThat(saved.status()).isEqualTo(200);

        assertThat(get("/api/admin/overview", receptionistToken, hotel.slug()).status()).isEqualTo(403);
    }

    @Test
    void reservedPermissionsCannotBeGivenToOtherRoles() {
        TestHotel hotel = newHotel();
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        Res res = call(HttpMethod.POST, "/api/admin/roles",
                Map.of("name", "Auditor", "permissions", List.of("AUDIT:VIEW")), adminToken, hotel.slug(), null);
        assertThat(res.status()).isEqualTo(403);
    }

    @Test
    void customRoleWithUserManagementCannotEscalateToAdmin() {
        TestHotel hotel = newHotel();
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        Res role = call(HttpMethod.POST, "/api/admin/roles",
                Map.of("name", "Jefe de turno", "permissions", List.of("USERS:VIEW", "USERS:MANAGE", "DASHBOARD:VIEW")),
                adminToken, hotel.slug(), null);
        assertThat(role.status()).isEqualTo(201);
        Long customRoleId = role.data().path("id").asLong();

        User chief = TenantContext.callAs(hotel.id(), () -> new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(s -> {
                    Role custom = roleRepository.findById(customRoleId).orElseThrow();
                    return userRepository.save(User.builder().email("jefe@" + hotel.slug() + ".mx").firstName("Jefe").lastName("Turno")
                            .passwordHash(passwordEncoder.encode(PASSWORD)).roles(new HashSet<>(Set.of(custom))).build());
                }));
        String chiefToken = token(chief.getEmail(), hotel.slug());

        Res asAdmin = call(HttpMethod.POST, "/api/admin/users", Map.of("email", "nuevo@" + hotel.slug() + ".mx",
                "firstName", "Nuevo", "lastName", "Admin", "roleIds", List.of(roleId(hotel, Role.ADMIN))), chiefToken, hotel.slug(), null);
        assertThat(asAdmin.status()).isEqualTo(403);

        Res withReception = call(HttpMethod.POST, "/api/admin/users", Map.of("email", "recep@" + hotel.slug() + ".mx",
                "firstName", "Nueva", "lastName", "Recepción", "roleIds", List.of(roleId(hotel, Role.RECEPTIONIST))), chiefToken, hotel.slug(), null);
        assertThat(withReception.status()).as("el rol de recepción otorga permisos que el jefe no tiene").isEqualTo(403);
    }

    @Test
    void lastActiveAdminIsProtected() {
        TestHotel hotel = newHotel();
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        User admin = TenantContext.callAs(hotel.id(), () -> userRepository.findByEmail(hotel.adminEmail()).orElseThrow());

        assertThat(call(HttpMethod.PATCH, "/api/admin/users/" + admin.getId() + "/active", Map.of("active", false),
                adminToken, hotel.slug(), null).status()).as("no puede desactivarse a sí mismo").isEqualTo(400);

        User second = createUser(hotel, Role.ADMIN);
        String secondToken = token(second.getEmail(), hotel.slug());
        assertThat(call(HttpMethod.PATCH, "/api/admin/users/" + admin.getId() + "/active", Map.of("active", false),
                secondToken, hotel.slug(), null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, "/api/admin/users/" + second.getId(), Map.of("firstName", "Prueba", "lastName", "Usuario",
                "roleIds", List.of(roleId(hotel, Role.RECEPTIONIST))), adminToken, hotel.slug(), null).status())
                .as("la cuenta desactivada ya no opera").isEqualTo(401);
    }

    @Test
    void permissionOverridesApplyAndRejectReservedCodes() {
        TestHotel hotel = newHotel();
        User receptionist = createUser(hotel, Role.RECEPTIONIST);
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        String receptionistToken = token(receptionist.getEmail(), hotel.slug());

        Res reserved = call(HttpMethod.PUT, "/api/admin/users/" + receptionist.getId() + "/permissions",
                Map.of("overrides", List.of(Map.of("code", "ROLES:MANAGE", "effect", "GRANT"))), adminToken, hotel.slug(), null);
        assertThat(reserved.status()).isEqualTo(403);

        Res ok = call(HttpMethod.PUT, "/api/admin/users/" + receptionist.getId() + "/permissions",
                Map.of("overrides", List.of(Map.of("code", "USERS:VIEW", "effect", "GRANT"),
                        Map.of("code", "DASHBOARD:VIEW", "effect", "DENY")), "reason", "Cubre turno nocturno"),
                adminToken, hotel.slug(), null);
        assertThat(ok.status()).isEqualTo(200);

        assertThat(get("/api/admin/users", receptionistToken, hotel.slug()).status()).isEqualTo(200);
        assertThat(get("/api/admin/overview", receptionistToken, hotel.slug()).status()).isEqualTo(403);
    }

    // ── Aislamiento entre hoteles y plataforma ────────────────────────────────────────

    @Test
    void adminCannotSeeOrTouchAnotherHotelEvenSendingItsHeader() {
        TestHotel a = newHotel();
        TestHotel b = newHotel();
        User userOfB = createUser(b, Role.RECEPTIONIST);
        String adminA = token(a.adminEmail(), a.slug());

        Res list = get("/api/admin/users", adminA, b.slug());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.data().path("content").toString()).doesNotContain(userOfB.getEmail()).doesNotContain(b.adminEmail());
        assertThat(get("/api/admin/users/" + userOfB.getId(), adminA, b.slug()).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/admin/users/" + userOfB.getId() + "/reset-password", null, adminA, b.slug(), null)
                .status()).isEqualTo(404);
    }

    @Test
    void platformAdminCreatesHotelsButCannotOperateThem() {
        String email = "super-" + java.util.UUID.randomUUID().toString().substring(0, 6) + "@nexora.mx";
        platformAccounts.createSuperAdmin(email, "Nexora", "Plataforma", PASSWORD);
        String superToken = token(email, null);

        String slug = "nuevo-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        Res created = call(HttpMethod.POST, "/api/platform/hotels", Map.of("name", "Hotel Playa", "slug", slug,
                "timezone", "America/Cancun", "adminFirstName", "José", "adminLastName", "Núñez",
                "adminEmail", "dueno@" + slug + ".mx"), superToken, null, null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("hotel").path("accessUrl").asText()).endsWith("/h/" + slug);
        String temporary = created.data().path("temporaryPassword").asText();
        assertThat(temporary).hasSize(14);

        assertThat(get("/api/admin/users", superToken, slug).status()).isEqualTo(403);

        // El dueño entra con la temporal y queda obligado a cambiarla antes de operar.
        Res ownerLogin = login("dueno@" + slug + ".mx", temporary, slug);
        assertThat(ownerLogin.status()).isEqualTo(200);
        assertThat(ownerLogin.data().path("user").path("mustChangePassword").asBoolean()).isTrue();
        String ownerToken = ownerLogin.data().path("accessToken").asText();
        assertThat(get("/api/admin/users", ownerToken, slug).status()).isEqualTo(403);
        Res changed = call(HttpMethod.POST, "/api/auth/change-password",
                Map.of("currentPassword", temporary, "newPassword", "MiHotel2026Seguro"), ownerToken, slug, null);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(get("/api/admin/users", changed.data().path("accessToken").asText(), slug).status()).isEqualTo(200);

        Res duplicated = call(HttpMethod.POST, "/api/platform/hotels", Map.of("name", "Otro", "slug", slug,
                "timezone", "America/Cancun", "adminFirstName", "Ana", "adminLastName", "Ruiz",
                "adminEmail", "otra@" + slug + ".mx"), superToken, null, null);
        assertThat(duplicated.status()).isEqualTo(409);
    }

    @Test
    void hotelStaffCannotCallPlatformEndpoints() {
        TestHotel hotel = newHotel();
        assertThat(get("/api/platform/hotels", token(hotel.adminEmail(), hotel.slug()), hotel.slug()).status()).isEqualTo(403);
    }

    @Test
    void sensitiveActionsLeaveAnAuditTrail() {
        TestHotel hotel = newHotel();
        String adminToken = token(hotel.adminEmail(), hotel.slug());
        login(hotel.adminEmail(), "Incorrecta123", hotel.slug());
        call(HttpMethod.POST, "/api/admin/roles", Map.of("name", "Mantenimiento", "permissions", List.of("ROOMS:VIEW")),
                adminToken, hotel.slug(), null);

        Res audit = get("/api/admin/audit", adminToken, hotel.slug());
        String actions = audit.data().path("content").toString();
        assertThat(actions).contains("LOGIN_SUCCESS", "LOGIN_FAILED", "ROLE_CREATED").doesNotContain("passwordHash");
    }
}
