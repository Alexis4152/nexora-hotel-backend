package com.nexora.hotel.domain;

import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.CANCELLED;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.CHECKED_OUT;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.CONFIRMED;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.EXPIRED;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.IN_HOUSE;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.NO_SHOW;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.PENDING;
import static com.nexora.hotel.enums.ReservationEnums.ReservationStatus.PENDING_PAYMENT;

/**
 * Transiciones permitidas de una reservación (docs/01 §C.6). Cualquier otra es un error de negocio: así no
 * existen confirmaciones repetidas, check-in de una cancelada ni estados inconsistentes.
 * <pre>
 * PENDING ─────────┬─► CONFIRMED ─► IN_HOUSE ─► CHECKED_OUT
 * PENDING_PAYMENT ─┘       ├─► NO_SHOW
 *      │                   └─► CANCELLED
 *      ├─► EXPIRED
 *      └─► CANCELLED
 * </pre>
 */
public final class ReservationStateMachine {

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED = new EnumMap<>(ReservationStatus.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED));
        ALLOWED.put(PENDING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED, EXPIRED));
        ALLOWED.put(CONFIRMED, EnumSet.of(IN_HOUSE, CANCELLED, NO_SHOW));
        ALLOWED.put(IN_HOUSE, EnumSet.of(CHECKED_OUT));
        for (ReservationStatus s : ReservationStatus.values()) {
            ALLOWED.putIfAbsent(s, EnumSet.noneOf(ReservationStatus.class));
        }
    }

    private ReservationStateMachine() {
    }

    public static boolean canTransition(ReservationStatus from, ReservationStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(ReservationStatus from, ReservationStatus to) {
        if (!canTransition(from, to)) {
            throw new BusinessException("Una reservación " + label(from) + " no puede pasar a " + label(to));
        }
    }

    public static String label(ReservationStatus s) {
        return switch (s) {
            case PENDING -> "pendiente";
            case PENDING_PAYMENT -> "pendiente de pago";
            case CONFIRMED -> "confirmada";
            case CANCELLED -> "cancelada";
            case NO_SHOW -> "no presentada";
            case IN_HOUSE -> "en estancia";
            case CHECKED_OUT -> "finalizada";
            case EXPIRED -> "expirada";
        };
    }
}
