package com.nexora.hotel.dto.response;

import java.time.Instant;
import java.util.Map;

public record AuditLogResponse(Long id, Instant createdAt, String actorEmail, String action, String entityType,
                               String entityId, String result, String detail, String ip,
                               Map<String, Object> before, Map<String, Object> after) {
}
