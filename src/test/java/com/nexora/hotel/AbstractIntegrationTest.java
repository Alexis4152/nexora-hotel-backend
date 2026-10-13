package com.nexora.hotel;

import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.repository.HotelRepository;
import com.nexora.hotel.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base de las pruebas de integración: aplicación real en puerto aleatorio + PostgreSQL real en
 * contenedor (un solo contenedor para toda la corrida). Se usa PostgreSQL y no H2 porque el sistema
 * depende de {@code daterange}, {@code EXCLUDE USING gist}, JSONB y triggers.
 * <p>
 * Cada prueba crea sus propios hoteles con slug aleatorio: no dependen del orden ni se pisan entre sí.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("nexora_hotel_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.payments.openpay-sandbox-url", OpenpayStub::start);
        registry.add("app.payments.timeout", () -> "PT5S");
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected HotelRepository hotelRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected Hotel createHotel(boolean active) {
        String slug = "hotel-" + UUID.randomUUID().toString().substring(0, 8);
        return TenantContext.callAs(TenantContext.ROOT, () -> hotelRepository.save(
                Hotel.builder().slug(slug).name("Hotel " + slug).active(active).build()));
    }
}
