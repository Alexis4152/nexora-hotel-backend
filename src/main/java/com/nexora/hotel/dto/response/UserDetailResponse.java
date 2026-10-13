package com.nexora.hotel.dto.response;

import java.util.List;

public record UserDetailResponse(UserResponse user, List<Override> overrides, List<String> effectivePermissions) {

    public record Override(String code, String effect, String reason) {
    }
}
