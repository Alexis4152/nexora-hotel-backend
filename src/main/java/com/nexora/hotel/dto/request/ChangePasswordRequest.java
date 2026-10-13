package com.nexora.hotel.dto.request;

import com.nexora.hotel.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Escribe tu contraseña actual") @Size(max = 72) String currentPassword,
        @NotBlank(message = "Escribe la nueva contraseña") @StrongPassword String newPassword) {
}
