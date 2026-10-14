package com.nexora.hotel.notification;

/**
 * Avisos de la campanita del panel. Cada uno le llega a quien tiene el permiso para atenderlo; a quien hizo la
 * acción no se le avisa de lo que él mismo hizo. El texto lo redacta la aplicación a partir del tipo y sus valores
 * (así se lee en el idioma de cada quien).
 */
public enum StaffNotificationType {

    // ── Reservaciones ──────────────────────────────────────────────────────────────────
    /** Nueva reservación: del portal (por confirmar o ya pagada) o capturada por otra persona del personal. */
    RESERVATION_CREATED(Category.RESERVATIONS, "RESERVATIONS:VIEW", null, Severity.INFO),
    RESERVATION_CONFIRMED(Category.RESERVATIONS, "RESERVATIONS:VIEW", null, Severity.SUCCESS),
    RESERVATION_MODIFIED(Category.RESERVATIONS, "RESERVATIONS:VIEW", null, Severity.INFO),
    RESERVATION_CANCELLED(Category.RESERVATIONS, "RESERVATIONS:VIEW", null, Severity.WARNING),
    /** Una solicitud por confirmar se liberó sola porque nadie la confirmó a tiempo. */
    RESERVATION_EXPIRED(Category.RESERVATIONS, "RESERVATIONS:VIEW", null, Severity.WARNING),

    // ── Pagos ──────────────────────────────────────────────────────────────────────────
    PAYMENT_RECEIVED(Category.PAYMENTS, "PAYMENTS:VIEW", null, Severity.SUCCESS),
    PAYMENT_FAILED(Category.PAYMENTS, "PAYMENTS:VIEW", null, Severity.DANGER),
    REFUND_ISSUED(Category.PAYMENTS, "PAYMENTS:VIEW", null, Severity.INFO),

    // ── Operación del día ──────────────────────────────────────────────────────────────
    ARRIVALS_TODAY(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", null, Severity.INFO),
    ROOM_READY(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", null, Severity.SUCCESS),
    /**
     * Una salida dejó la habitación por limpiar. Es para quien limpia, no para el mostrador: recepción y el
     * administrador (que registran las salidas) ya lo saben y no necesitan un aviso por cada check-out.
     */
    ROOM_TO_CLEAN(Category.OPERATIONS, "HOUSEKEEPING:UPDATE", "RESERVATIONS:CHECK_OUT", Severity.INFO),
    LATE_CHECKOUT(Category.OPERATIONS, "RESERVATIONS:CHECK_OUT", null, Severity.WARNING),
    NO_SHOW_CANDIDATE(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", null, Severity.WARNING),

    // ── Clientes ───────────────────────────────────────────────────────────────────────
    DATA_REQUEST_CREATED(Category.CUSTOMERS, "CUSTOMERS:UPDATE", null, Severity.WARNING);

    public enum Category { RESERVATIONS, PAYMENTS, OPERATIONS, CUSTOMERS }

    public enum Severity { INFO, SUCCESS, WARNING, DANGER }

    private final Category category;
    private final String permission;
    private final String unlessPermission;
    private final Severity severity;

    StaffNotificationType(Category category, String permission, String unlessPermission, Severity severity) {
        this.category = category;
        this.permission = permission;
        this.unlessPermission = unlessPermission;
        this.severity = severity;
    }

    public Category category() {
        return category;
    }

    /** Permiso que debe tener quien recibe el aviso. */
    public String permission() {
        return permission;
    }

    /** Quien tenga este permiso NO lo recibe aunque tenga el anterior (null: no hay excepción). */
    public String unlessPermission() {
        return unlessPermission;
    }

    public Severity severity() {
        return severity;
    }
}
