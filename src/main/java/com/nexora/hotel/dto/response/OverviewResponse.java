package com.nexora.hotel.dto.response;

import java.time.Instant;
import java.util.List;

/** Resumen del panel. Ocupación e ingresos se agregan en la Fase 9 (no se muestran cifras inventadas). */
public record OverviewResponse(HotelRef hotel, long activeStaff, long inactiveStaff, List<RoleCount> roles,
                               List<RecentLogin> recentLogins, List<AuditLogResponse> recentActivity, Operations operations) {

    /**
     * Operación de hoy en la zona del hotel. {@code occupiedTonight} cuenta habitaciones con reservación confirmada o
     * en estancia que incluye la noche de hoy (no son ingresos: los importes llegan con pagos en la Fase 7).
     */
    public record Operations(long arrivalsToday, long departuresToday, long inHouse, long pending, long occupiedTonight,
                             long activeRooms, long blockedTonight) {
    }

    public record RoleCount(String code, String name, long users) {
    }

    public record RecentLogin(String name, String email, Instant at) {
    }
}
