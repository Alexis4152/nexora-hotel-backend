package com.nexora.hotel.dto.request;

import jakarta.validation.constraints.NotNull;

public record SetActiveRequest(@NotNull Boolean active) {
}
