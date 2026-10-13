package com.nexora.hotel.dto.request;

import com.nexora.hotel.enums.OverrideEffect;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Reemplaza todos los ajustes de permisos de un usuario. */
public record UserOverridesRequest(
        @NotNull @Valid List<Item> overrides,
        @Size(max = 300) String reason) {

    public record Item(@NotBlank String code, @NotNull OverrideEffect effect) {
    }
}
