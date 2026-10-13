package com.nexora.hotel.enums;

/** Enumeraciones de pagos y caja (reflejan los CHECK de V7). */
public final class PaymentEnums {

    private PaymentEnums() {
    }

    /** PAYMENT suma a lo pagado; REFUND (dinero devuelto) y REVERSAL (corrección de un registro) restan. */
    public enum PaymentKind {
        PAYMENT, REFUND, REVERSAL;

        public boolean isOutflow() {
            return this != PAYMENT;
        }
    }

    public enum PaymentMethod {
        CASH, CARD, TRANSFER, ONLINE;

        /** Cobros hechos en recepción (pasan por la caja). */
        public boolean isPresencial() {
            return this != ONLINE;
        }
    }

    public enum PaymentStatus {
        PENDING, COMPLETED, FAILED, CANCELLED;

        public boolean isFinal() {
            return this != PENDING;
        }
    }

    public enum CashSessionStatus { OPEN, CLOSED }

    public enum CashMovementType { PAYMENT, REFUND, REVERSAL, WITHDRAWAL, DEPOSIT }

    public enum Direction { IN, OUT }

    public enum IdType { INE, PASSPORT, LICENSE, OTHER }
}
