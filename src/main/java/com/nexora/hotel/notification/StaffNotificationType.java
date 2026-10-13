package com.nexora.hotel.notification;

/**
 * Avisos de la campanita del panel. Cada uno le llega a quien tiene el permiso para atenderlo; a quien hizo la
 * acción no se le avisa de lo que él mismo hizo. El texto lo redacta la aplicación a partir del tipo y sus valores
 * (así se lee en el idioma de cada quien).
 */
public enum StaffNotificationType {

    // ── Reservaciones ──────────────────────────────────────────────────────────────────
    /** Nueva reservación: del portal (por confirmar o ya pagada) o capturada por otra persona del personal. */
    RESERVATION_CREATED(Category.RESERVATIONS, "RESERVATIONS:VIEW", Severity.INFO),
    RESERVATION_CONFIRMED(Category.RESERVATIONS, "RESERVATIONS:VIEW", Severity.SUCCESS),
    RESERVATION_MODIFIED(Category.RESERVATIONS, "RESERVATIONS:VIEW", Severity.INFO),
    RESERVATION_CANCELLED(Category.RESERVATIONS, "RESERVATIONS:VIEW", Severity.WARNING),
    /** Una solicitud por confirmar se liberó sola porque nadie la confirmó a tiempo. */
    RESERVATION_EXPIRED(Category.RESERVATIONS, "RESERVATIONS:VIEW", Severity.WARNING),

    // ── Pagos ──────────────────────────────────────────────────────────────────────────
    PAYMENT_RECEIVED(Category.PAYMENTS, "PAYMENTS:VIEW", Severity.SUCCESS),
    PAYMENT_FAILED(Category.PAYMENTS, "PAYMENTS:VIEW", Severity.DANGER),
    REFUND_ISSUED(Category.PAYMENTS, "PAYMENTS:VIEW", Severity.INFO),

    // ── Operación del día ──────────────────────────────────────────────────────────────
    ARRIVALS_TODAY(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", Severity.INFO),
    ROOM_READY(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", Severity.SUCCESS),
    LATE_CHECKOUT(Category.OPERATIONS, "RESERVATIONS:CHECK_OUT", Severity.WARNING),
    NO_SHOW_CANDIDATE(Category.OPERATIONS, "RESERVATIONS:CHECK_IN", Severity.WARNING),

    // ── Clientes ───────────────────────────────────────────────────────────────────────
    DATA_REQUEST_CREATED(Category.CUSTOMERS, "CUSTOMERS:UPDATE", Severity.WARNING);

    public enum Category { RESERVATIONS, PAYMENTS, OPERATIONS, CUSTOMERS }

    public enum Severity { INFO, SUCCESS, WARNING, DANGER }

    private final Category category;
    private final String permission;
    private final Severity severity;

    StaffNotificationType(Category category, String permission, Severity severity) {
        this.category = category;
        this.permission = permission;
        this.severity = severity;
    }

    public Category category() {
        return category;
    }

    /** Permiso que debe tener quien recibe el aviso. */
    public String permission() {
        return permission;
    }

    public Severity severity() {
        return severity;
    }
}
