package com.nexora.hotel.dto.response;

/** El access token viaja en el cuerpo y el frontend lo guarda solo en memoria; el refresh va en cookie httpOnly. */
public record SessionResponse(String accessToken, long expiresIn, SessionUser user) {
}
