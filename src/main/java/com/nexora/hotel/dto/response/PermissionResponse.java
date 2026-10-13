package com.nexora.hotel.dto.response;

public record PermissionResponse(String code, String module, String action, String description,
                                 boolean reserved, boolean sensitive) {
}
