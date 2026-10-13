package com.nexora.hotel.service;

import com.nexora.hotel.domain.CancellationEvaluator;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CalendarEntry;
import com.nexora.hotel.dto.catalog.ReservationDtos.CalendarResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CalendarRoom;
import com.nexora.hotel.dto.catalog.ReservationDtos.CancellationPreview;
import com.nexora.hotel.dto.catalog.ReservationDtos.DayBoard;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationSummary;
import com.nexora.hotel.dto.catalog.ReservationDtos.ServiceLine;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.ReservationRoom;
import com.nexora.hotel.enums.CatalogEnums.PenaltyType;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.OccupancyRepository;
import com.nexora.hotel.repository.ReservationLedger;
import com.nexora.hotel.repository.ReservationRepository;
import com.nexora.hotel.repository.RoomRepository;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lecturas de reservaciones del hotel actual (sin efectos). */
@Service
@RequiredArgsConstructor
public class ReservationQueryService {

    private final ReservationRepository repository;
    private final ReservationLedger ledger;
    private final OccupancyRepository occupancyRepository;
    private final RoomRepository roomRepository;
    private final AccountService accountService;
    private final HotelDirectory hotelDirectory;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<ReservationSummary> list(String query, List<ReservationStatus> statuses, LocalDate from, LocalDate to,
                                                 int page, int size) {
        Specification<Reservation> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("folio")), like), cb.like(cb.lower(root.get("contactName")), like),
                        cb.like(cb.lower(root.get("contactEmail")), like), cb.like(root.get("contactPhone"), like)));
            }
            if (statuses != null && !statuses.isEmpty()) {
                p.add(root.get("status").in(statuses));
            }
            // Rango: estancias que tocan [from, to].
            if (from != null) {
                p.add(cb.greaterThan(root.get("checkOut"), from));
            }
            if (to != null) {
                p.add(cb.lessThanOrEqualTo(root.get("checkIn"), to));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var result = repository.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.ASC, "checkIn").and(Sort.by("id"))));
        return PageResponse.of(result, this::summary);
    }

    @Transactional(readOnly = true)
    public DayBoard board() {
        LocalDate today = today();
        List<Reservation> arrivals = repository.findAll((r, q, cb) -> cb.and(cb.equal(r.get("checkIn"), today),
                r.get("status").in(ReservationStatus.CONFIRMED, ReservationStatus.PENDING, ReservationStatus.PENDING_PAYMENT)));
        List<Reservation> departures = repository.findAll((r, q, cb) -> cb.and(cb.equal(r.get("checkOut"), today),
                r.get("status").in(ReservationStatus.IN_HOUSE, ReservationStatus.CONFIRMED)));
        List<Reservation> inHouse = repository.findAll((r, q, cb) -> cb.equal(r.get("status"), ReservationStatus.IN_HOUSE));
        List<Reservation> pending = repository.findAll((r, q, cb) -> r.get("status").in(ReservationStatus.PENDING, ReservationStatus.PENDING_PAYMENT),
                Sort.by("checkIn"));
        return new DayBoard(arrivals.stream().map(this::summary).toList(), departures.stream().map(this::summary).toList(),
                inHouse.stream().map(this::summary).toList(), pending.stream().map(this::summary).toList());
    }

    @Transactional(readOnly = true)
    public ReservationDetail detail(Long id) {
        return detail(find(id));
    }

    @Transactional(readOnly = true)
    public List<ReservationSummary> forCustomer(Long customerId) {
        return repository.findByCustomerIdOrderByCheckInDesc(customerId).stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public CalendarResponse calendar(LocalDate from, LocalDate to) {
        if (from == null || to == null || !to.isAfter(from) || ChronoUnit.DAYS.between(from, to) > 62) {
            throw new BusinessException("El rango del calendario debe ser de 1 a 62 días");
        }
        long hotelId = TenantContext.requireHotelId();
        List<CalendarRoom> rooms = roomRepository.findAllByOrderByFloorAscSortOrderAscNumberAsc().stream()
                .filter(r -> r.isActive())
                .map(r -> new CalendarRoom(r.getId(), r.getNumber(), r.getFloor(), r.getRoomType().getName(),
                        r.getOperationalStatus().name()))
                .toList();
        List<CalendarEntry> entries = occupancyRepository.calendar(hotelId, from, to).stream()
                .map(m -> new CalendarEntry(((Number) m.get("room_id")).longValue(), (String) m.get("kind"),
                        ((Date) m.get("starts")).toLocalDate(), ((Date) m.get("ends")).toLocalDate(),
                        m.get("reservation_id") == null ? null : ((Number) m.get("reservation_id")).longValue(),
                        (String) m.get("folio"), (String) m.get("contact_name"), (String) m.get("status"),
                        (String) m.get("reason")))
                .toList();
        return new CalendarResponse(from, to, rooms, entries);
    }

    // ── Apoyo compartido con ReservationService ────────────────────────────────────────

    public Reservation find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Reservación no encontrada"));
    }

    public ReservationDetail detail(Reservation r) {
        long hotelId = TenantContext.requireHotelId();
        ReservationRoom room = r.getRooms().isEmpty() ? null : r.getRooms().get(0);
        int revision = r.getPriceRevision();
        return new ReservationDetail(summary(r), r.getCustomer().getId(), r.getContactEmail(), r.getSpecialRequests(),
                r.getInternalNotes(), r.getRoomSubtotal(), r.getExtraGuestsTotal(), r.getServicesTotal(), r.getDiscountTotal(),
                r.getTaxTotal(), r.getPenaltyAmount(), revision,
                ledger.priceLines(hotelId, r.getId()).stream().filter(l -> l.revision() == revision).toList(),
                services(r), ledger.history(hotelId, r.getId()), r.getPolicySnapshot(), preview(r),
                room == null ? null : room.getRoom().getId(), room == null ? null : room.getRoomType().getId(),
                r.getExpiresAt(), r.getConfirmedAt(), r.getCancelledAt(), r.getCancelReason(), r.getVersion(),
                accountService.account(r));
    }

    public List<ServiceLine> services(Reservation r) {
        return r.getServices().stream().map(s -> new ServiceLine(s.getExtraServiceId(), s.getName(), s.getChargeMode(),
                s.getUnits(), s.getQuantity(), s.getUnitPrice(), s.getTotal())).toList();
    }

    public ReservationSummary summary(Reservation r) {
        ReservationRoom room = r.getRooms().isEmpty() ? null : r.getRooms().get(0);
        return new ReservationSummary(r.getId(), r.getFolio(), r.getStatus(), r.getSource(), r.getContactName(),
                r.getContactPhone(), r.getCheckIn(), r.getCheckOut(), r.getNights(), r.getAdults(), r.getChildren(),
                room == null ? null : room.getRoom().getNumber(), room == null ? null : room.getRoomType().getName(),
                r.getTotal(), r.getCreatedAt(), r.getPaidTotal(), r.balance());
    }

    /** Vista previa de cancelación con la política ACEPTADA (snapshot), o null si ya no se puede cancelar. */
    public CancellationPreview preview(Reservation r) {
        if (!(r.getStatus() == ReservationStatus.PENDING || r.getStatus() == ReservationStatus.PENDING_PAYMENT
                || r.getStatus() == ReservationStatus.CONFIRMED)) {
            return null;
        }
        CancellationEvaluator.Result result = evaluate(r);
        return new CancellationPreview(result.outcome(), result.penalty(), result.freeUntil(), result.checkInAt());
    }

    public CancellationEvaluator.Result evaluate(Reservation r) {
        Hotel hotel = hotelDirectory.current();
        Map<String, Object> p = r.getPolicySnapshot();
        CancellationEvaluator.Policy policy = new CancellationEvaluator.Policy(
                ((Number) p.getOrDefault("freeCancelHours", 0)).intValue(),
                PenaltyType.valueOf(String.valueOf(p.getOrDefault("lateCancelPenaltyType", "NONE"))),
                new BigDecimal(String.valueOf(p.getOrDefault("lateCancelPenaltyValue", "0"))));
        LocalTime checkInTime = LocalTime.parse(String.valueOf(p.getOrDefault("checkInTime", "15:00")));
        BigDecimal firstNight = new BigDecimal(String.valueOf(p.getOrDefault("firstNight", "0")));
        return CancellationEvaluator.evaluate(policy, r.getStatus() == ReservationStatus.CONFIRMED, r.getCheckIn(),
                checkInTime, hotel.zoneId(), clock.instant(), firstNight, r.getTotal());
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(hotelDirectory.current().zoneId()));
    }
}
