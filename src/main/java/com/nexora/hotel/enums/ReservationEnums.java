package com.nexora.hotel.enums;

/** Enumeraciones de reservaciones y notificaciones (reflejan CHECKs de V6). */
public final class ReservationEnums {

    private ReservationEnums() {
    }

    public enum ReservationStatus {
        /** Solicitud por confirmar (portal sin pago en línea, o recepción sin confirmar). Ocupa la habitación. */
        PENDING,
        /** Esperando el anticipo en línea; se confirma sola al verificarse el pago con la pasarela. */
        PENDING_PAYMENT,
        CONFIRMED,
        CANCELLED,
        NO_SHOW,
        IN_HOUSE,
        CHECKED_OUT,
        /** Se venció sin confirmarse o pagarse. */
        EXPIRED;

        /** Estados que mantienen ocupada la habitación. */
        public boolean holdsRoom() {
            return this == PENDING || this == PENDING_PAYMENT || this == CONFIRMED || this == IN_HOUSE;
        }

        public boolean isFinal() {
            return this == CANCELLED || this == NO_SHOW || this == CHECKED_OUT || this == EXPIRED;
        }
    }

    public enum ReservationSource { WEB, FRONT_DESK, PHONE, EMAIL, OTHER }

    public enum PriceLineType { NIGHT, EXTRA_GUEST, SERVICE, DISCOUNT, TAX, TAX_INCLUDED }

    public enum OutboxStatus { PENDING, SENT, FAILED, SKIPPED }

    /** Eventos que generan correo. */
    public enum NotificationEvent {
        RESERVATION_CREATED, RESERVATION_CONFIRMED, RESERVATION_CANCELLED, RESERVATION_CHANGED, RESERVATION_ACCESS_LINK,
        PAYMENT_LINK, PAYMENT_RECEIVED, REFUND_ISSUED, STAY_COMPLETED, ACCOUNT_VERIFY, DATA_REQUEST_RECEIVED
    }
}
