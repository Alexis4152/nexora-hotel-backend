package com.nexora.hotel.dto.request;

import com.nexora.hotel.validation.MxPhone;
import com.nexora.hotel.validation.PersonName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank(message = "Escribe el correo") @Email(message = "El correo no es válido") @Size(max = 150) String email,
        @NotBlank(message = "Escribe el nombre") @Size(max = 100) @PersonName String firstName,
        @NotBlank(message = "Escribe los apellidos") @Size(max = 100) @PersonName String lastName,
        @MxPhone String phone,
        @NotEmpty(message = "Asigna al menos un rol") Set<Long> roleIds) {
}
