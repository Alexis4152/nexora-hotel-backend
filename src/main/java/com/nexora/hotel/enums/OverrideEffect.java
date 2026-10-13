package com.nexora.hotel.enums;

/** Efecto de un ajuste de permiso por usuario. */
public enum OverrideEffect {
    /** Otorga un permiso que sus roles no le dan. */
    GRANT,
    /** Quita un permiso que sus roles sí le dan. Gana sobre GRANT. */
    DENY
}
