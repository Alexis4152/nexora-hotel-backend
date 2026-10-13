package com.nexora.hotel.domain;

import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
import com.nexora.hotel.enums.ReservationEnums.PriceLineType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Precio completo de una estancia: noches (del {@link PricingEngine}), huéspedes adicionales, descuento manual,
 * servicios y luego impuestos. Produce las líneas que se guardan como desglose congelado de la reservación.
 * <ul>
 *   <li>El descuento aplica solo al hospedaje y no puede superarlo.</li>
 *   <li>Impuestos ROOM sobre hospedaje neto, SERVICE sobre servicios, ALL sobre ambos.</li>
 *   <li>Impuesto incluido en precio: solo se desglosa, no se suma.</li>
 * </ul>
 */
public final class StayPricing {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record ServiceItem(Long serviceId, String name, ChargeMode mode, BigDecimal unitPrice, int units) {
    }

    public record Line(PriceLineType type, LocalDate date, String description, BigDecimal quantity,
                       BigDecimal unitAmount, BigDecimal amount) {
    }

    public record ChargedService(ServiceItem item, int quantity, BigDecimal total) {
    }

    public record Result(List<Line> lines, List<ChargedService> services, BigDecimal roomSubtotal,
                         BigDecimal extraGuestsTotal, BigDecimal servicesTotal, BigDecimal discountTotal,
                         BigDecimal taxTotal, BigDecimal total, BigDecimal firstNight) {
    }

    private StayPricing() {
    }

    public static Result compute(PricingEngine.Quote lodging, int adults, int children, List<ServiceItem> services,
                                 BigDecimal discount, List<PricingEngine.Tax> taxes) {
        int nights = lodging.nights().size();
        int guests = adults + children;
        List<Line> lines = new ArrayList<>();
        for (PricingEngine.NightLine n : lodging.nights()) {
            lines.add(new Line(PriceLineType.NIGHT, n.date(), n.ruleName() == null ? "Noche" : "Noche · " + n.ruleName(),
                    BigDecimal.ONE, n.price(), n.price()));
            if (n.extraGuests().signum() > 0) {
                lines.add(new Line(PriceLineType.EXTRA_GUEST, n.date(), "Huéspedes adicionales", BigDecimal.ONE,
                        n.extraGuests(), n.extraGuests()));
            }
        }
        BigDecimal lodgingGross = lodging.roomSubtotal().add(lodging.extraGuestsSubtotal());
        BigDecimal disc = money(discount == null ? BigDecimal.ZERO : discount.max(BigDecimal.ZERO));
        if (disc.compareTo(lodgingGross) > 0) {
            throw new IllegalArgumentException("El descuento no puede superar el importe del hospedaje");
        }
        if (disc.signum() > 0) {
            lines.add(new Line(PriceLineType.DISCOUNT, null, "Descuento", BigDecimal.ONE, disc.negate(), disc.negate()));
        }

        List<ChargedService> charged = new ArrayList<>();
        BigDecimal servicesTotal = BigDecimal.ZERO;
        for (ServiceItem s : services) {
            int quantity = switch (s.mode()) {
                case PER_STAY -> s.units();
                case PER_NIGHT -> s.units() * nights;
                case PER_PERSON -> s.units() * guests;
                case PER_PERSON_NIGHT -> s.units() * guests * nights;
                case PER_UNIT -> s.units();
            };
            BigDecimal total = money(s.unitPrice().multiply(BigDecimal.valueOf(quantity)));
            charged.add(new ChargedService(s, quantity, total));
            servicesTotal = servicesTotal.add(total);
            lines.add(new Line(PriceLineType.SERVICE, null, s.name(), BigDecimal.valueOf(quantity), money(s.unitPrice()), total));
        }

        BigDecimal lodgingNet = lodgingGross.subtract(disc);
        BigDecimal added = BigDecimal.ZERO;
        for (PricingEngine.Tax tax : taxes) {
            BigDecimal base = switch (tax.appliesTo()) {
                case ROOM -> lodgingNet;
                case SERVICE -> servicesTotal;
                case ALL -> lodgingNet.add(servicesTotal);
            };
            if (base.signum() == 0) {
                continue;
            }
            String label = tax.name() + " " + tax.percent().stripTrailingZeros().toPlainString() + "%";
            if (tax.includedInPrice()) {
                BigDecimal divisor = BigDecimal.ONE.add(tax.percent().divide(HUNDRED, 10, RoundingMode.HALF_UP));
                BigDecimal amount = money(base.subtract(base.divide(divisor, 10, RoundingMode.HALF_UP)));
                lines.add(new Line(PriceLineType.TAX_INCLUDED, null, label + " (incluido)", BigDecimal.ONE, amount, amount));
            } else {
                BigDecimal amount = money(base.multiply(tax.percent()).divide(HUNDRED, 10, RoundingMode.HALF_UP));
                added = added.add(amount);
                lines.add(new Line(PriceLineType.TAX, null, label, BigDecimal.ONE, amount, amount));
            }
        }

        BigDecimal firstNight = lodging.nights().isEmpty() ? BigDecimal.ZERO
                : lodging.nights().get(0).price().add(lodging.nights().get(0).extraGuests());
        return new Result(lines, charged, money(lodging.roomSubtotal()), money(lodging.extraGuestsSubtotal()),
                money(servicesTotal), disc, money(added), money(lodgingNet.add(servicesTotal).add(added)), money(firstNight));
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
