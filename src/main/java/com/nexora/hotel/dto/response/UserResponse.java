package com.nexora.hotel.dto.response;

import java.time.Instant;
import java.util.List;

public record UserResponse(Long id, String email, String firstName, String lastName, String phone, boolean active,
                           boolean mustChangePassword, boolean locked, Instant lastLoginAt, Instant createdAt,
                           List<RoleRef> roles) {
}
