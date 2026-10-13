package com.nexora.hotel.domain;

import com.nexora.hotel.enums.CatalogEnums.AdjustmentType;
import com.nexora.hotel.enums.CatalogEnums.RateRuleType;
import com.nexora.hotel.enums.CatalogEnums.TaxAppliesTo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Motor de precios por noche. Lógica pura (sin Spring ni BD) para probarse a fondo.
 * <p>
 * Por cada noche de [entrada, salida) se elige UNA regla aplicable:
 * <ol>
 *   <li>Mayor precedencia de tipo: DATE_OVERRIDE &gt; HOLIDAY &gt; SEASON &gt; WEEKEND.</li>
 *   <li>Empate: mayor {@code priority}.</li>
 *   <li>Empate: la más reciente (id mayor).</li>
 * </ol>
 * Sin regla aplica el precio base del tipo. Una regla con {@code minNights} solo aplica si la estancia
 * completa tiene al menos esas noches. Luego se suman huéspedes adicionales y, al final, impuestos.
 * Todo en {@link BigDecimal}, redondeo HALF_UP a 2 decimales por línea; nunca float/double.
 */
public final class PricingEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record RoomTypeRate(Long id, BigDecimal basePrice, int includedOccupancy,
                               BigDecimal extraAdultFee, BigDecimal extraChildFee) {
    }

    public record Rule(Long id, String name, Long roomTypeId, RateRuleType type, LocalDate from, LocalDate to,
                       Set<DayOfWeek> days, AdjustmentType adjustment, BigDecimal value, Integer minNights,
                       int priority) {

        /** Para reglas SEASON, {@code from/to} son las fechas de la temporada. */
        boolean appliesTo(LocalDate night, Long roomType, long stayNights) {
            if (roomTypeId != null && !roomTypeId.equals(roomType)) {
                return false;
            }
            if (minNights != null && stayNights < minNights) {
                return false;
            }
            boolean inRange = (from == null || !night.isBefore(from)) && (to == null || !night.isAfter(to));
            return switch (type) {
                case DATE_OVERRIDE, HOLIDAY, SEASON -> from != null && to != null && inRange;
                case WEEKEND -> days != null && days.contains(night.getDayOfWeek()) && inRange;
            };
        }
    }

    public record Tax(String name, BigDecimal percent, TaxAppliesTo appliesTo, boolean includedInPrice) {
    }

    public record NightLine(LocalDate date, BigDecimal base, BigDecimal price, String ruleName, Long ruleId,
                            BigDecimal extraGuests) {
    }

    public record TaxLine(String name, BigDecimal percent, boolean included, BigDecimal amount) {
    }

    public record Quote(List<NightLine> nights, BigDecimal roomSubtotal, BigDecimal extraGuestsSubtotal,
                        BigDecimal subtotal, List<TaxLine> taxes, BigDecimal taxesAdded, BigDecimal total) {
    }

    private PricingEngine() {
    }

    public static Quote quote(RoomTypeRate roomType, LocalDate checkIn, LocalDate checkOut, int adults, int children,
                              List<Rule> rules, List<Tax> taxes) {
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("La fecha de salida debe ser posterior a la de entrada");
        }
        long stayNights = java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
        BigDecimal extraPerNight = extraGuestsPerNight(roomType, adults, children);

        List<NightLine> nights = new ArrayList<>();
        BigDecimal roomSubtotal = BigDecimal.ZERO;
        BigDecimal extraSubtotal = BigDecimal.ZERO;
        for (LocalDate night = checkIn; night.isBefore(checkOut); night = night.plusDays(1)) {
            LocalDate d = night;
            Optional<Rule> winner = rules.stream()
                    .filter(r -> r.appliesTo(d, roomType.id(), stayNights))
                    .max(Comparator.comparingInt((Rule r) -> r.type().rank())
                            .thenComparingInt(Rule::priority)
                            .thenComparing(Rule::id, Comparator.nullsFirst(Comparator.naturalOrder())));
            BigDecimal price = winner.map(r -> apply(roomType.basePrice(), r)).orElse(money(roomType.basePrice()));
            nights.add(new NightLine(d, money(roomType.basePrice()), price, winner.map(Rule::name).orElse(null),
                    winner.map(Rule::id).orElse(null), extraPerNight));
            roomSubtotal = roomSubtotal.add(price);
            extraSubtotal = extraSubtotal.add(extraPerNight);
        }

        BigDecimal subtotal = roomSubtotal.add(extraSubtotal);
        List<TaxLine> taxLines = new ArrayList<>();
        BigDecimal added = BigDecimal.ZERO;
        for (Tax tax : taxes) {
            if (tax.appliesTo() == TaxAppliesTo.SERVICE) {
                continue;
            }
            BigDecimal amount;
            if (tax.includedInPrice()) {
                // Desglose de lo ya incluido: subtotal − subtotal / (1 + p)
                BigDecimal divisor = BigDecimal.ONE.add(tax.percent().divide(HUNDRED, 10, RoundingMode.HALF_UP));
                amount = money(subtotal.subtract(subtotal.divide(divisor, 10, RoundingMode.HALF_UP)));
            } else {
                amount = money(subtotal.multiply(tax.percent()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
                added = added.add(amount);
            }
            taxLines.add(new TaxLine(tax.name(), tax.percent(), tax.includedInPrice(), amount));
        }
        return new Quote(nights, money(roomSubtotal), money(extraSubtotal), money(subtotal), taxLines, money(added),
                money(subtotal.add(added)));
    }

    /** Adultos llenan primero la ocupación incluida; los menores que excedan pagan su tarifa. */
    static BigDecimal extraGuestsPerNight(RoomTypeRate rt, int adults, int children) {
        int extraAdults = Math.max(0, adults - rt.includedOccupancy());
        int remaining = Math.max(0, rt.includedOccupancy() - adults);
        int extraChildren = Math.max(0, children - remaining);
        return money(rt.extraAdultFee().multiply(BigDecimal.valueOf(extraAdults))
                .add(rt.extraChildFee().multiply(BigDecimal.valueOf(extraChildren))));
    }

    static BigDecimal apply(BigDecimal base, Rule rule) {
        BigDecimal result = switch (rule.adjustment()) {
            case FIXED_PRICE -> rule.value();
            case AMOUNT -> base.add(rule.value());
            case PERCENT -> base.add(base.multiply(rule.value()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        };
        return money(result.max(BigDecimal.ZERO));
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** "FRIDAY,SATURDAY" → {FRIDAY, SATURDAY}; ignora valores inválidos. */
    public static Set<DayOfWeek> parseDays(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        Map<String, DayOfWeek> valid = Arrays.stream(DayOfWeek.values())
                .collect(Collectors.toMap(Enum::name, d -> d));
        return Arrays.stream(csv.split(",")).map(String::trim).map(valid::get).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
