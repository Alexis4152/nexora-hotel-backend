package com.nexora.hotel.enums;

/**
 * Enumeraciones del catálogo hotelero. Se agrupan aquí porque son cortas y se usan juntas; cada una
 * refleja un CHECK de la base de datos (V3–V5).
 */
public final class CatalogEnums {

    private CatalogEnums() {
    }

    /** Estado operativo actual de la habitación (distinto de su disponibilidad por fechas). */
    public enum RoomStatus { AVAILABLE, OCCUPIED, CLEANING, MAINTENANCE, OUT_OF_SERVICE }

    public enum PenaltyType { NONE, FIRST_NIGHT, PERCENT, FIXED }

    public enum SeasonKind { HIGH, LOW, HOLIDAY }

    /** Orden de precedencia: el de mayor {@code rank} gana cuando varias reglas aplican a la misma noche. */
    public enum RateRuleType {
        WEEKEND(1), SEASON(2), HOLIDAY(3), DATE_OVERRIDE(4);

        private final int rank;

        RateRuleType(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public enum AdjustmentType { FIXED_PRICE, PERCENT, AMOUNT }

    public enum TaxAppliesTo { ROOM, SERVICE, ALL }

    public enum ChargeMode { PER_STAY, PER_NIGHT, PER_PERSON, PER_PERSON_NIGHT, PER_UNIT }

    public enum MaintenanceStatus { OPEN, RELEASED }
}
