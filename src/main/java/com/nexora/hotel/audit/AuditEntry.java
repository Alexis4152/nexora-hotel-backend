package com.nexora.hotel.audit;

import com.nexora.hotel.enums.AuditResult;
import lombok.Builder;

import java.util.Map;

/**
 * Evento a registrar. {@code before}/{@code after} solo con datos permitidos: nunca contraseñas, hashes,
 * tokens, credenciales ni datos de tarjeta.
 *
 * @param hotelId solo se usa en operaciones de plataforma (ROOT); dentro de un hotel lo pone Hibernate
 * @param actorUserId si es null se toma del usuario autenticado
 */
@Builder
public record AuditEntry(String action, String entityType, Object entityId, Map<String, Object> before,
                         Map<String, Object> after, AuditResult result, String detail,
                         Long actorUserId, String actorEmail, Long hotelId) {

    public static AuditEntryBuilder success(String action, String entityType, Object entityId) {
        return builder().action(action).entityType(entityType).entityId(entityId).result(AuditResult.SUCCESS);
    }

    public static AuditEntryBuilder of(String action, AuditResult result) {
        return builder().action(action).result(result);
    }
}
