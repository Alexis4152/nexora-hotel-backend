package com.nexora.hotel.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido") @Size(max = 150) String email,
        @NotBlank(message = "Escribe tu contraseña") @Size(max = 72) String password) {
}
