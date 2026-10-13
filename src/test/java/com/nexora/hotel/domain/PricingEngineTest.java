package com.nexora.hotel.domain;

import com.nexora.hotel.domain.PricingEngine.Quote;
import com.nexora.hotel.domain.PricingEngine.RoomTypeRate;
import com.nexora.hotel.domain.PricingEngine.Rule;
import com.nexora.hotel.domain.PricingEngine.Tax;
import com.nexora.hotel.enums.CatalogEnums.AdjustmentType;
import com.nexora.hotel.enums.CatalogEnums.RateRuleType;
import com.nexora.hotel.enums.CatalogEnums.TaxAppliesTo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingEngineTest {

    private static final RoomTypeRate DOUBLE = new RoomTypeRate(1L, new BigDecimal("1500.00"), 2,
            new BigDecimal("350.00"), new BigDecimal("200.00"));
    // 2026-12-04 es viernes.
    private static final LocalDate FRI = LocalDate.of(2026, 12, 4);

    private static Rule weekend(long id, String value) {
        return new Rule(id, "Fin de semana", null, RateRuleType.WEEKEND, null, null,
                Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), AdjustmentType.PERCENT, new BigDecimal(value), null, 0);
    }

    private static Rule season(long id, LocalDate from, LocalDate to, String fixed, int priority) {
        return new Rule(id, "Temporada " + id, null, RateRuleType.SEASON, from, to, null,
                AdjustmentType.FIXED_PRICE, new BigDecimal(fixed), null, priority);
    }

    @Test
    void checkoutDayIsNotANight() {
        Quote q = PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(2), 2, 0, List.of(), List.of());
        assertThat(q.nights()).hasSize(2);
        assertThat(q.total()).isEqualByComparingTo("3000.00");
    }

    @Test
    void invalidDatesAreRejected() {
        assertThatThrownBy(() -> PricingEngine.quote(DOUBLE, FRI, FRI, 2, 0, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weekendRuleOnlyAffectsItsDays() {
        // Jueves, viernes, sábado → solo viernes y sábado +20 %.
        Quote q = PricingEngine.quote(DOUBLE, FRI.minusDays(1), FRI.plusDays(2), 2, 0, List.of(weekend(1, "20")), List.of());
        assertThat(q.nights()).extracting(PricingEngine.NightLine::price)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1500"), new BigDecimal("1800"), new BigDecimal("1800"));
    }

    @Test
    void higherRankWinsOverPriority() {
        Rule seasonRule = season(2, FRI.minusDays(10), FRI.plusDays(10), "2500.00", 0);
        Rule weekendRule = new Rule(3L, "Fin de semana VIP", null, RateRuleType.WEEKEND, null, null,
                Set.of(DayOfWeek.FRIDAY), AdjustmentType.FIXED_PRICE, new BigDecimal("9999"), null, 100);
        Quote q = PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(1), 2, 0, List.of(weekendRule, seasonRule), List.of());
        assertThat(q.nights().get(0).price()).isEqualByComparingTo("2500.00");
        assertThat(q.nights().get(0).ruleName()).isEqualTo("Temporada 2");
    }

    @Test
    void sameRankUsesPriorityThenNewest() {
        Rule low = season(4, FRI, FRI, "2000.00", 1);
        Rule high = season(5, FRI, FRI, "2200.00", 5);
        Rule newerSamePriority = season(6, FRI, FRI, "2300.00", 5);
        assertThat(PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(1), 2, 0, List.of(low, high), List.of())
                .total()).isEqualByComparingTo("2200.00");
        assertThat(PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(1), 2, 0, List.of(low, high, newerSamePriority), List.of())
                .total()).isEqualByComparingTo("2300.00");
    }

    @Test
    void ruleForAnotherRoomTypeOrBelowMinNightsDoesNotApply() {
        Rule otherType = new Rule(7L, "Suite", 99L, RateRuleType.DATE_OVERRIDE, FRI, FRI, null,
                AdjustmentType.FIXED_PRICE, new BigDecimal("5000"), null, 0);
        Rule minStay = new Rule(8L, "Larga estancia", null, RateRuleType.DATE_OVERRIDE, FRI, FRI.plusDays(10), null,
                AdjustmentType.PERCENT, new BigDecimal("-15"), 3, 0);
        assertThat(PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(1), 2, 0, List.of(otherType, minStay), List.of())
                .total()).isEqualByComparingTo("1500.00");
        assertThat(PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(3), 2, 0, List.of(minStay), List.of())
                .total()).isEqualByComparingTo("3825.00");
    }

    @Test
    void extraGuestsAreChargedOverIncludedOccupancy() {
        // 3 adultos + 1 menor con 2 incluidos: 1 adulto extra (350) + 1 menor extra (200) por noche.
        Quote q = PricingEngine.quote(DOUBLE, FRI.minusDays(1), FRI, 3, 1, List.of(), List.of());
        assertThat(q.extraGuestsSubtotal()).isEqualByComparingTo("550.00");
        // 1 adulto + 1 menor caben en la ocupación incluida.
        assertThat(PricingEngine.quote(DOUBLE, FRI.minusDays(1), FRI, 1, 1, List.of(), List.of())
                .extraGuestsSubtotal()).isEqualByComparingTo("0.00");
    }

    @Test
    void taxesAddedOrJustBrokenDown() {
        List<Tax> taxes = List.of(
                new Tax("IVA", new BigDecimal("16"), TaxAppliesTo.ALL, false),
                new Tax("ISH", new BigDecimal("3"), TaxAppliesTo.ROOM, false),
                new Tax("Servicio", new BigDecimal("10"), TaxAppliesTo.SERVICE, false));
        Quote q = PricingEngine.quote(DOUBLE, FRI.minusDays(1), FRI, 2, 0, List.of(), taxes);
        assertThat(q.taxesAdded()).isEqualByComparingTo("285.00");
        assertThat(q.total()).isEqualByComparingTo("1785.00");
        assertThat(q.taxes()).hasSize(2);

        Quote included = PricingEngine.quote(DOUBLE, FRI.minusDays(1), FRI, 2, 0, List.of(),
                List.of(new Tax("IVA", new BigDecimal("16"), TaxAppliesTo.ALL, true)));
        assertThat(included.total()).isEqualByComparingTo("1500.00");
        assertThat(included.taxes().get(0).amount()).isEqualByComparingTo("206.90");
    }

    @Test
    void priceNeverGoesNegativeAndRoundsHalfUp() {
        Rule huge = new Rule(9L, "Error de captura", null, RateRuleType.DATE_OVERRIDE, FRI, FRI, null,
                AdjustmentType.AMOUNT, new BigDecimal("-5000"), null, 0);
        assertThat(PricingEngine.quote(DOUBLE, FRI, FRI.plusDays(1), 2, 0, List.of(huge), List.of()).total())
                .isEqualByComparingTo("0.00");
        RoomTypeRate odd = new RoomTypeRate(1L, new BigDecimal("999.99"), 2, BigDecimal.ZERO, BigDecimal.ZERO);
        Rule pct = new Rule(10L, "x", null, RateRuleType.DATE_OVERRIDE, FRI, FRI, null, AdjustmentType.PERCENT,
                new BigDecimal("12.5"), null, 0);
        assertThat(PricingEngine.quote(odd, FRI, FRI.plusDays(1), 2, 0, List.of(pct), List.of()).total())
                .isEqualByComparingTo("1124.99");
    }

    @Test
    void parsesDaysIgnoringGarbage() {
        assertThat(PricingEngine.parseDays("FRIDAY, SATURDAY,FUNDAY")).containsExactlyInAnyOrder(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    }
}
