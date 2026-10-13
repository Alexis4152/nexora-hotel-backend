package com.nexora.hotel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record SaveRoleRequest(
        @NotBlank(message = "Escribe el nombre del rol") @Size(min = 3, max = 80) String name,
        @Size(max = 200) String description,
        @NotNull Set<String> permissions) {
}
