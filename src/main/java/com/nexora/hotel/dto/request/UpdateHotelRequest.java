package com.nexora.hotel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateHotelRequest(
        @NotBlank(message = "Escribe el nombre del hotel") @Size(max = 150) String name,
        @NotBlank @Size(max = 60) String timezone,
        @NotNull Boolean active) {
}
