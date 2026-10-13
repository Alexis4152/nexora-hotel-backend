package com.nexora.hotel.domain;

import com.nexora.hotel.enums.CatalogEnums.PenaltyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Decide si una cancelación procede sin penalización, con penalización o requiere intervención administrativa,
 * usando la política ACEPTADA al reservar (no la vigente), la hora exacta del check-in en la zona del hotel y la
 * hora actual. Lógica pura para probarla con un reloj fijo.
 */
public final class CancellationEvaluator {

    public enum Outcome {
        /** Dentro del plazo gratuito, o la reservación nunca se confirmó. */
        FREE,
        /** Fuera de plazo pero antes del check-in: aplica la penalización de la política. */
        WITH_PENALTY,
        /** Ya pasó la hora de llegada: debe resolverlo el hotel (no-show o excepción autorizada). */
        REQUIRES_ADMIN
    }

    public record Policy(int freeCancelHours, PenaltyType penaltyType, BigDecimal penaltyValue) {
    }

    public record Result(Outcome outcome, BigDecimal penalty, ZonedDateTime freeUntil, ZonedDateTime checkInAt) {
    }

    private CancellationEvaluator() {
    }

    /**
     * @param confirmed    false si la reservación aún estaba pendiente (el hotel no se había comprometido)
     * @param firstNight   importe de la primera noche (hospedaje + huéspedes extra) para penalización FIRST_NIGHT
     * @param total        total de la reservación para penalización PERCENT/FIXED
     */
    public static Result evaluate(Policy policy, boolean confirmed, LocalDate checkIn, LocalTime checkInTime, ZoneId zone,
                                  Instant now, BigDecimal firstNight, BigDecimal total) {
        ZonedDateTime checkInAt = ZonedDateTime.of(checkIn, checkInTime, zone);
        ZonedDateTime freeUntil = checkInAt.minusHours(policy.freeCancelHours());
        if (!confirmed) {
            return new Result(Outcome.FREE, BigDecimal.ZERO.setScale(2), freeUntil, checkInAt);
        }
        if (now.isBefore(freeUntil.toInstant())) {
            return new Result(Outcome.FREE, BigDecimal.ZERO.setScale(2), freeUntil, checkInAt);
        }
        if (!now.isBefore(checkInAt.toInstant())) {
            return new Result(Outcome.REQUIRES_ADMIN, penalty(policy, firstNight, total), freeUntil, checkInAt);
        }
        return new Result(Outcome.WITH_PENALTY, penalty(policy, firstNight, total), freeUntil, checkInAt);
    }

    /** Penalización por tipo (también la usa el no-show), acotada a [0, total]. */
    public static BigDecimal penalty(Policy policy, BigDecimal firstNight, BigDecimal total) {
        BigDecimal value = switch (policy.penaltyType()) {
            case NONE -> BigDecimal.ZERO;
            case FIRST_NIGHT -> firstNight;
            case PERCENT -> total.multiply(policy.penaltyValue()).divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
            case FIXED -> policy.penaltyValue();
        };
        return value.min(total).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
