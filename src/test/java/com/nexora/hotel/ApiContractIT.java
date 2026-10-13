package com.nexora.hotel;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.entity.Hotel;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Resolución del hotel por header, respuestas de error con el contrato ApiResponse y salud. */
class ApiContractIT extends AbstractIntegrationTest {

    private ResponseEntity<ApiResponse<Map<String, Object>>> getPublicHotel(String slug) {
        HttpHeaders headers = new HttpHeaders();
        if (slug != null) {
            headers.set("X-Hotel", slug);
        }
        return restTemplate.exchange(url("/api/public/hotel"), HttpMethod.GET, new HttpEntity<>(headers),
                new ParameterizedTypeReference<>() {});
    }

    @Test
    void publicHotelIsResolvedFromHeader() {
        Hotel hotel = createHotel(true);

        var response = getPublicHotel(hotel.getSlug());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).containsEntry("slug", hotel.getSlug())
                .containsEntry("timezone", "America/Mexico_City").containsEntry("currency", "MXN");
    }

    @Test
    void unknownOrInactiveHotelIsNotFound() {
        Hotel inactive = createHotel(false);

        assertThat(getPublicHotel("no-existe-este-hotel").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getPublicHotel(inactive.getSlug()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getPublicHotel("../../etc/passwd").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void publicEndpointWithoutHotelExplainsWhatIsMissing() {
        var response = getPublicHotel(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("hotel");
    }

    @Test
    void protectedEndpointWithoutSessionReturnsJson401WithRequestId() {
        ResponseEntity<ApiResponse<Void>> response = restTemplate.exchange(url("/api/admin/users"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
    }

    @Test
    void healthIsUpWithoutDetails() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(url("/actuator/health"), HttpMethod.GET,
                null, new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP").doesNotContainKey("components");
    }
}
