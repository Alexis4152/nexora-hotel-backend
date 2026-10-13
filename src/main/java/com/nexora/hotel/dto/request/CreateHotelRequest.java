package com.nexora.hotel.dto.request;

import com.nexora.hotel.validation.PersonName;
import com.nexora.hotel.validation.ValidationPatterns;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Alta de un hotel por el SUPER_ADMIN, junto con su primer administrador. */
public record CreateHotelRequest(
        @NotBlank(message = "Escribe el nombre del hotel") @Size(max = 150) String name,
        @NotBlank(message = "Escribe el identificador del enlace")
        @Pattern(regexp = ValidationPatterns.SLUG, message = "Solo minúsculas, números y guiones (3 a 60)") String slug,
        @NotBlank @Size(max = 60) String timezone,
        @NotBlank(message = "Escribe el nombre del administrador") @Size(max = 100) @PersonName String adminFirstName,
        @NotBlank(message = "Escribe los apellidos del administrador") @Size(max = 100) @PersonName String adminLastName,
        @NotBlank(message = "Escribe el correo del administrador") @Email(message = "El correo no es válido")
        @Size(max = 150) String adminEmail) {
}
