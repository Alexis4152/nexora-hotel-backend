package com.nexora.hotel.enums;

/** Resultado de la operación registrada en la bitácora de auditoría. */
public enum AuditResult {
    SUCCESS,
    /** Rechazada por falta de permisos o por una regla de seguridad. */
    DENIED,
    FAILED
}
