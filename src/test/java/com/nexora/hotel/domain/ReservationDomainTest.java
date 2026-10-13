package com.nexora.hotel.domain;

import com.nexora.hotel.domain.CancellationEvaluator.Outcome;
import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
import com.nexora.hotel.enums.CatalogEnums.PenaltyType;
import com.nexora.hotel.enums.CatalogEnums.TaxAppliesTo;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationDomainTest {

    private static final ZoneId CANCUN = ZoneId.of("America/Cancun");

    // ── Máquina de estados ─────────────────────────────────────────────────────────

    @Test
    void validTransitions() {
        assertThat(ReservationStateMachine.canTransition(ReservationStatus.PENDING, ReservationStatus.CONFIRMED)).isTrue();
        assertThat(ReservationStateMachine.canTransition(ReservationStatus.CONFIRMED, ReservationStatus.IN_HOUSE)).isTrue();
        assertThat(ReservationStateMachine.canTransition(ReservationStatus.IN_HOUSE, ReservationStatus.CHECKED_OUT)).isTrue();
    }

    @Test
    void repeatedOrInconsistentTransitionsAreRejected() {
        assertThatThrownBy(() -> ReservationStateMachine.requireTransition(ReservationStatus.CONFIRMED, ReservationStatus.CONFIRMED))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReservationStateMachine.requireTransition(ReservationStatus.CANCELLED, ReservationStatus.CONFIRMED))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReservationStateMachine.requireTransition(ReservationStatus.PENDING, ReservationStatus.IN_HOUSE))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReservationStateMachine.requireTransition(ReservationStatus.IN_HOUSE, ReservationStatus.CANCELLED))
                .isInstanceOf(BusinessException.class);
    }

    // ── Cancelación ────────────────────────────────────────────────────────────────

    private static final CancellationEvaluator.Policy POLICY_48H = new CancellationEvaluator.Policy(48, PenaltyType.FIRST_NIGHT, BigDecimal.ZERO);
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 12, 20);
    private static final LocalTime CHECK_IN_TIME = LocalTime.of(15, 0);

    private static CancellationEvaluator.Result at(String instant, boolean confirmed, CancellationEvaluator.Policy p) {
        return CancellationEvaluator.evaluate(p, confirmed, CHECK_IN, CHECK_IN_TIME, CANCUN, Instant.parse(instant),
                new BigDecimal("2600.00"), new BigDecimal("9746.10"));
    }

    @Test
    void cancellationWithinFreeWindowUsesHotelTimezone() {
        // Check-in 20/dic 15:00 Cancún (UTC-5) = 20:00 UTC. Gratis hasta 18/dic 20:00 UTC.
        assertThat(at("2026-12-18T19:59:00Z", true, POLICY_48H).outcome()).isEqualTo(Outcome.FREE);
        assertThat(at("2026-12-18T20:00:00Z", true, POLICY_48H).outcome()).isEqualTo(Outcome.WITH_PENALTY);
    }

    @Test
    void latePenaltyFollowsTheAcceptedPolicy() {
        assertThat(at("2026-12-19T10:00:00Z", true, POLICY_48H).penalty()).isEqualByComparingTo("2600.00");
        var percent = new CancellationEvaluator.Policy(48, PenaltyType.PERCENT, new BigDecimal("50"));
        assertThat(at("2026-12-19T10:00:00Z", true, percent).penalty()).isEqualByComparingTo("4873.05");
        var fixedHuge = new CancellationEvaluator.Policy(48, PenaltyType.FIXED, new BigDecimal("99999"));
        assertThat(at("2026-12-19T10:00:00Z", true, fixedHuge).penalty()).as("nunca más que el total").isEqualByComparingTo("9746.10");
        var none = new CancellationEvaluator.Policy(48, PenaltyType.NONE, BigDecimal.ZERO);
        assertThat(at("2026-12-19T10:00:00Z", true, none).penalty()).isEqualByComparingTo("0.00");
    }

    @Test
    void afterArrivalTimeRequiresAdminAndPendingIsAlwaysFree() {
        assertThat(at("2026-12-20T20:00:00Z", true, POLICY_48H).outcome()).isEqualTo(Outcome.REQUIRES_ADMIN);
        assertThat(at("2026-12-20T19:00:00Z", false, POLICY_48H).outcome()).isEqualTo(Outcome.FREE);
    }

    // ── Precio de la estancia ──────────────────────────────────────────────────────

    @Test
    void stayTotalsWithServicesDiscountAndTaxes() {
        PricingEngine.Quote lodging = PricingEngine.quote(new PricingEngine.RoomTypeRate(1L, new BigDecimal("1000"), 2,
                new BigDecimal("300"), BigDecimal.ZERO), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), 3, 0, List.of(), List.of());
        List<StayPricing.ServiceItem> services = List.of(
                new StayPricing.ServiceItem(1L, "Desayuno", ChargeMode.PER_PERSON_NIGHT, new BigDecimal("200"), 1),
                new StayPricing.ServiceItem(2L, "Traslado", ChargeMode.PER_UNIT, new BigDecimal("650"), 2));
        List<PricingEngine.Tax> taxes = List.of(
                new PricingEngine.Tax("IVA", new BigDecimal("16"), TaxAppliesTo.ALL, false),
                new PricingEngine.Tax("ISH", new BigDecimal("3"), TaxAppliesTo.ROOM, false));

        StayPricing.Result r = StayPricing.compute(lodging, 3, 0, services, new BigDecimal("100"), taxes);

        // Hospedaje 2×1000 + extra 2×300 = 2600 − 100 = 2500; servicios 200×3×2=1200 + 650×2=1300 → 2500
        assertThat(r.servicesTotal()).isEqualByComparingTo("2500.00");
        // IVA 16% × 5000 = 800; ISH 3% × 2500 = 75
        assertThat(r.taxTotal()).isEqualByComparingTo("875.00");
        assertThat(r.total()).isEqualByComparingTo("5875.00");
        assertThat(r.firstNight()).isEqualByComparingTo("1300.00");
        assertThat(r.services().get(0).quantity()).isEqualTo(6);
    }

    @Test
    void discountCannotExceedLodging() {
        PricingEngine.Quote lodging = PricingEngine.quote(new PricingEngine.RoomTypeRate(1L, new BigDecimal("1000"), 2,
                BigDecimal.ZERO, BigDecimal.ZERO), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), 2, 0, List.of(), List.of());
        assertThatThrownBy(() -> StayPricing.compute(lodging, 2, 0, List.of(), new BigDecimal("1000.01"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
