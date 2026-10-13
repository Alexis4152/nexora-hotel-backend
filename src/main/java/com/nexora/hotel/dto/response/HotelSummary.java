package com.nexora.hotel.dto.response;

import java.time.Instant;

/** Hotel visto desde la plataforma, con el enlace que se entrega al dueño. */
public record HotelSummary(Long id, String slug, String name, String timezone, String currency, boolean active,
                           Instant createdAt, long staffCount, String accessUrl) {
}
