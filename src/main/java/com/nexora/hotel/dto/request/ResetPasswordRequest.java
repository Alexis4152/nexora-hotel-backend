package com.nexora.hotel.dto.request;

import com.nexora.hotel.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 100) String token,
        @NotBlank(message = "Escribe la nueva contraseña") @StrongPassword String newPassword) {
}
