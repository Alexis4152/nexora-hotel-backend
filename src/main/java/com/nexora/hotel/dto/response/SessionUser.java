package com.nexora.hotel.dto.response;

import java.util.List;

/** Usuario de la sesión. El frontend arma menús y botones con {@code permissions}; el backend vuelve a validar todo. */
public record SessionUser(Long id, String email, String firstName, String lastName, boolean platform,
                          HotelRef hotel, List<String> roles, List<String> permissions, boolean mustChangePassword) {
}
