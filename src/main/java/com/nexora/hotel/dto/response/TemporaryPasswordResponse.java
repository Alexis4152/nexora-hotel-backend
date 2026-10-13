package com.nexora.hotel.dto.response;

/** Contraseña temporal recién generada: se muestra una sola vez y obliga a cambiarla al entrar. */
public record TemporaryPasswordResponse(String email, String temporaryPassword) {
}
