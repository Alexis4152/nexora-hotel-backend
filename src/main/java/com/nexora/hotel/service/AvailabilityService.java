package com.nexora.hotel.service;

import com.nexora.hotel.domain.PricingEngine;
import com.nexora.hotel.domain.StayPricing;
import com.nexora.hotel.dto.catalog.ReservationDtos.AvailabilityOption;
import com.nexora.hotel.dto.catalog.ReservationDtos.AvailabilityResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.NightPrice;
import com.nexora.hotel.entity.Amenity;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.HotelSettings;
import com.nexora.hotel.entity.RoomImage;
import com.nexora.hotel.entity.RoomType;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.repository.OccupancyRepository;
import com.nexora.hotel.repository.RoomImageRepository;
import com.nexora.hotel.repository.RoomTypeRepository;
import com.nexora.hotel.storage.MediaUrls;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Motor de disponibilidad: qué tipos de habitación pueden alojar a los huéspedes en [entrada, salida) y a qué
 * precio. Considera reservaciones, retenciones vigentes y bloqueos de mantenimiento; ignora habitaciones
 * inactivas o fuera de servicio. Las reglas del hotel (anticipación, noches mín/máx, mismo día) se aplican al
 * portal; el personal solo tiene las restricciones básicas (no fechas pasadas).
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final RoomTypeRepository roomTypeRepository;
    private final RoomImageRepository imageRepository;
    private final OccupancyRepository occupancyRepository;
    private final HotelSettingsService settingsService;
    private final RateService rateService;
    private final HotelDirectory hotelDirectory;
    private final MediaUrls mediaUrls;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AvailabilityResponse search(LocalDate checkIn, LocalDate checkOut, int adults, int children, boolean publicChannel) {
        validateStay(checkIn, checkOut, adults, children, publicChannel);
        long hotelId = TenantContext.requireHotelId();
        Map<Long, Long> available = occupancyRepository.availableCountByType(hotelId, checkIn, checkOut);
        List<RoomType> types = roomTypeRepository.findByActiveTrueOrderBySortOrderAscNameAsc();
        Map<Long, String> covers = types.isEmpty() ? Map.of()
                : imageRepository.findByRoomTypeIdInOrderBySortOrderAscIdAsc(types.stream().map(RoomType::getId).toList())
                .stream().collect(Collectors.toMap(RoomImage::getRoomTypeId, i -> mediaUrls.of(i.getFile()), (a, b) -> a));
        List<PricingEngine.Tax> taxes = rateService.activeTaxes();

        List<AvailabilityOption> options = types.stream()
                .filter(t -> !publicChannel || available.getOrDefault(t.getId(), 0L) > 0)
                .map(t -> option(t, checkIn, checkOut, adults, children, available.getOrDefault(t.getId(), 0L).intValue(),
                        covers.get(t.getId()), taxes))
                .sorted(Comparator.comparing((AvailabilityOption o) -> !(o.fitsGuests() && o.availableRooms() > 0))
                        .thenComparing(AvailabilityOption::total))
                .toList();
        return new AvailabilityResponse(checkIn, checkOut, (int) ChronoUnit.DAYS.between(checkIn, checkOut), adults,
                children, publicChannel ? options.stream().filter(AvailabilityOption::fitsGuests).toList() : options);
    }

    private AvailabilityOption option(RoomType t, LocalDate in, LocalDate out, int adults, int children, int free,
                                      String cover, List<PricingEngine.Tax> taxes) {
        boolean fits = fits(t, adults, children);
        // Si no caben, se cotiza con la ocupación máxima del tipo solo para mostrar un precio de referencia.
        int a = fits ? adults : Math.min(adults, t.getMaxAdults());
        int c = fits ? children : Math.max(0, Math.min(children, t.getMaxOccupancy() - a));
        PricingEngine.Quote lodging = rateService.lodging(t, in, out, a, c);
        StayPricing.Result price = StayPricing.compute(lodging, a, c, List.of(), BigDecimal.ZERO, taxes);
        BigDecimal lodgingTotal = price.roomSubtotal().add(price.extraGuestsTotal());
        int nights = lodging.nights().size();
        return new AvailabilityOption(t.getId(), t.getName(), t.getDescription(), t.getMaxAdults(), t.getMaxChildren(),
                t.getMaxOccupancy(), t.getBedType(), t.getBedCount(), t.getSizeM2(), cover,
                t.getAmenities().stream().filter(Amenity::isActive).map(Amenity::getName).sorted().toList(),
                free, fits, lodgingTotal, price.taxTotal(), price.total(),
                lodgingTotal.divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP),
                lodging.nights().stream().map(n -> new NightPrice(n.date(), n.price(), n.ruleName())).toList());
    }

    public static boolean fits(RoomType t, int adults, int children) {
        return adults <= t.getMaxAdults() && children <= t.getMaxChildren() && adults + children <= t.getMaxOccupancy();
    }

    /**
     * Reglas de fechas. Siempre: salida posterior a entrada, sin fechas pasadas (zona del hotel), máximo 365 noches.
     * Portal: además anticipación mínima/máxima, mismo día y noches mínimas/máximas configuradas.
     */
    public void validateStay(LocalDate checkIn, LocalDate checkOut, int adults, int children, boolean publicChannel) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new BusinessException("La fecha de salida debe ser posterior a la de entrada");
        }
        if (adults < 1) {
            throw new BusinessException("Debe haber al menos un adulto");
        }
        if (children < 0) {
            throw new BusinessException("La cantidad de menores no es válida");
        }
        Hotel hotel = hotelDirectory.current();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(hotel.zoneId()));
        LocalDate today = now.toLocalDate();
        if (checkIn.isBefore(today)) {
            throw new BusinessException("No se puede reservar en fechas pasadas");
        }
        long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > 365) {
            throw new BusinessException("La estancia no puede superar 365 noches");
        }
        if (!publicChannel) {
            return;
        }
        HotelSettings s = settingsService.settings();
        if (checkIn.equals(today) && !s.isAllowSameDayBooking()) {
            throw new BusinessException("No se aceptan reservaciones en línea para el mismo día. Llámanos y con gusto te ayudamos.");
        }
        // Sin anticipación mínima no se compara: quien reserva hoy después de la hora de check-in llega más tarde.
        ZonedDateTime arrival = checkIn.atTime(s.getCheckInTime()).atZone(hotel.zoneId());
        if (s.getMinBookingLeadHours() > 0
                && Duration.between(now, arrival).toMinutes() < s.getMinBookingLeadHours() * 60L) {
            throw new BusinessException("Reserva con al menos " + s.getMinBookingLeadHours() + " horas de anticipación");
        }
        if (checkIn.isAfter(today.plusDays(s.getMaxBookingLeadDays()))) {
            throw new BusinessException("Solo se puede reservar con hasta " + s.getMaxBookingLeadDays() + " días de anticipación");
        }
        if (nights < s.getMinNights() || nights > s.getMaxNights()) {
            throw new BusinessException("La estancia debe ser de " + s.getMinNights() + " a " + s.getMaxNights() + " noches");
        }
    }
}
