package com.nexora.hotel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.request.CreateHotelRequest;
import com.nexora.hotel.dto.response.ProvisionedHotelResponse;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.service.HotelProvisioningService;
import com.nexora.hotel.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Utilidades para pruebas de seguridad: hoteles, usuarios y llamadas HTTP con token, hotel y cookie. */
public abstract class SecurityTestSupport extends AbstractIntegrationTest {

    protected static final String PASSWORD = "Prueba12345";

    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected HotelProvisioningService provisioning;
    @Autowired protected UserRepository userRepository;
    @Autowired protected RoleRepository roleRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected PlatformTransactionManager txManager;

    /** Respuesta ya parseada. */
    protected record Res(int status, JsonNode body, HttpHeaders headers) {
        JsonNode data() {
            return body.path("data");
        }
    }

    /** Hotel nuevo con su ADMIN (contraseña conocida, sin cambio obligatorio). */
    protected record TestHotel(Long id, String slug, String adminEmail) {
    }

    protected TestHotel newHotel() {
        String slug = "h-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "admin@" + slug + ".mx";
        ProvisionedHotelResponse r = provisioning.createWithPassword(
                new CreateHotelRequest("Hotel " + slug, slug, "America/Mexico_City", "Ana", "López", email), PASSWORD);
        return new TestHotel(r.hotel().id(), slug, email);
    }

    protected User createUser(TestHotel hotel, String roleCode) {
        String email = roleCode.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 6) + "@" + hotel.slug() + ".mx";
        return TenantContext.callAs(hotel.id(), () -> new TransactionTemplate(txManager).execute(s -> {
            Role role = roleRepository.findByCode(roleCode).orElseThrow();
            return userRepository.save(User.builder().email(email).firstName("Prueba").lastName("Usuario")
                    .passwordHash(passwordEncoder.encode(PASSWORD)).roles(new HashSet<>(Set.of(role))).build());
        }));
    }

    protected Res login(String email, String password, String hotelSlug) {
        return call(HttpMethod.POST, "/api/auth/login", java.util.Map.of("email", email, "password", password),
                null, hotelSlug, null);
    }

    protected String token(String email, String hotelSlug) {
        Res res = login(email, PASSWORD, hotelSlug);
        if (res.status() != 200) {
            throw new IllegalStateException("Login falló: " + res.body());
        }
        return res.data().path("accessToken").asText();
    }

    protected static String refreshCookie(Res res) {
        List<String> cookies = res.headers().get(HttpHeaders.SET_COOKIE);
        return cookies == null ? null : cookies.stream().filter(c -> c.startsWith("nx_rt_"))
                .map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElse(null);
    }

    protected Res get(String path, String token, String hotelSlug) {
        return call(HttpMethod.GET, path, null, token, hotelSlug, null);
    }

    protected Res call(HttpMethod method, String path, Object body, String token, String hotelSlug, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Requested-With", "XMLHttpRequest");
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (hotelSlug != null) {
            headers.set("X-Hotel", hotelSlug);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        ResponseEntity<String> response = restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
        try {
            JsonNode json = response.getBody() == null ? objectMapper.createObjectNode() : objectMapper.readTree(response.getBody());
            return new Res(response.getStatusCode().value(), json, response.getHeaders());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Long roleId(TestHotel hotel, String code) {
        return TenantContext.callAs(hotel.id(), () -> roleRepository.findByCode(code).orElseThrow().getId());
    }
}
