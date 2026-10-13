package com.nexora.hotel.dto.response;

/** Resultado del alta: lo que el SUPER_ADMIN entrega al dueño. La contraseña temporal solo se muestra una vez. */
public record ProvisionedHotelResponse(HotelSummary hotel, String adminEmail, String temporaryPassword) {
}
