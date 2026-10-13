package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.domain.ReservationStateMachine;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckInPreview;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckInRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckOutRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.GuestRegistration;
import com.nexora.hotel.dto.catalog.PaymentDtos.NoShowRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.RoomOption;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.ReservationGuest;
import com.nexora.hotel.entity.ReservationRoom;
import com.nexora.hotel.entity.Room;
import com.nexora.hotel.enums.CatalogEnums.RoomStatus;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.repository.OccupancyRepository;
import com.nexora.hotel.repository.ReservationGuestRepository;
import com.nexora.hotel.repository.ReservationLedger;
import com.nexora.hotel.repository.RoomRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Llegada, salida y no-show. Cada operación bloquea la reservación y valida la transición con la máquina de estados:
 * no hay check-in ni check-out duplicados. Las excepciones (saldo pendiente al salir, habitación aún en limpieza,
 * condonar penalización) requieren {@code RESERVATIONS:OVERRIDE} y quedan en auditoría.
 */
@Service
@RequiredArgsConstructor
public class StayOperationsService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-MX"));

    private final ReservationService reservations;
    private final ReservationQueryService queries;
    private final OccupancyRepository occupancy;
    private final RoomRepository rooms;
    private final ReservationGuestRepository guests;
    private final ReservationLedger ledger;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ── Check-in ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CheckInPreview checkInPreview(Long id) {
        Reservation r = queries.find(id);
        LocalDate today = queries.today();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        switch (r.getStatus()) {
            case CONFIRMED -> { }
            case PENDING -> blockers.add("Confirma la reservación antes de registrar la llegada");
            case PENDING_PAYMENT -> blockers.add("La reservación espera su anticipo: cóbralo o confírmala antes de la llegada");
            case IN_HOUSE -> blockers.add("La llegada ya se registró");
            default -> blockers.add("La reservación está " + ReservationStateMachine.label(r.getStatus()));
        }
        if (today.isBefore(r.getCheckIn())) {
            blockers.add("La llegada es el " + r.getCheckIn().format(DATE));
        } else if (!today.isBefore(r.getCheckOut())) {
            blockers.add("La fecha de salida ya pasó");
        } else if (today.isAfter(r.getCheckIn())) {
            long late = ChronoUnit.DAYS.between(r.getCheckIn(), today);
            warnings.add("Llega con " + late + " día(s) de retraso: las noches no usadas se cobran según la reservación");
        }
        BigDecimal balance = r.balance();
        if (balance.signum() > 0) {
            warnings.add("Saldo pendiente de " + ReservationService.moneyText(balance, r.getCurrency()));
        }
        ReservationRoom current = r.getRooms().isEmpty() ? null : r.getRooms().get(0);
        RoomOption currentOption = current == null ? null : option(current.getRoom(), true);
        if (current != null && current.getRoom().getOperationalStatus() != RoomStatus.AVAILABLE) {
            warnings.add("La habitación " + current.getRoom().getNumber() + " está " + statusLabel(current.getRoom().getOperationalStatus())
                    + ": elige otra o confirma que ya está lista");
        }
        List<RoomOption> options = new ArrayList<>();
        if (blockers.isEmpty()) {
            for (Room room : rooms.findAllByOrderByFloorAscSortOrderAscNumberAsc()) {
                boolean isCurrent = current != null && room.getId().equals(current.getRoom().getId());
                if (isCurrent || (room.isActive() && room.getOperationalStatus() == RoomStatus.AVAILABLE
                        && AvailabilityService.fits(room.getRoomType(), r.getAdults(), r.getChildren())
                        && occupancy.isFree(room.getId(), r.getCheckIn(), r.getCheckOut(), r.getId()))) {
                    options.add(option(room, isCurrent));
                }
            }
        }
        return new CheckInPreview(blockers.isEmpty(), blockers, warnings, today, balance,
                ReservationService.depositPending(r), currentOption, options);
    }

    @Transactional
    public ReservationDetail checkIn(Long id, CheckInRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        long hotelId = TenantContext.requireHotelId();
        Reservation r = reservations.lockForUpdate(id);
        ReservationStateMachine.requireTransition(r.getStatus(), ReservationStatus.IN_HOUSE);
        LocalDate today = queries.today();
        if (today.isBefore(r.getCheckIn())) {
            throw new BusinessException("La llegada es el " + r.getCheckIn().format(DATE) + ": aún no se puede registrar");
        }
        if (!today.isBefore(r.getCheckOut())) {
            throw new BusinessException("La fecha de salida ya pasó: no se puede registrar la llegada");
        }
        if (!req.identityVerified()) {
            throw new BusinessException("Confirma que verificaste la identificación del huésped");
        }
        BigDecimal balance = r.balance();
        if (balance.signum() > 0 && !req.acceptPendingBalance()) {
            throw new BusinessException("Hay un saldo pendiente de " + ReservationService.moneyText(balance, r.getCurrency())
                    + ": cóbralo o confirma que se liquidará a la salida");
        }

        ReservationRoom rr = r.getRooms().get(0);
        Room previous = rr.getRoom();
        Long targetId = req.roomId() == null ? previous.getId() : req.roomId();
        Room room = rooms.findByIdForUpdate(targetId).orElseThrow(() -> new BusinessException("Habitación no encontrada"));
        boolean moved = !room.getId().equals(previous.getId());
        if (moved) {
            if (!room.isActive() || !AvailabilityService.fits(room.getRoomType(), r.getAdults(), r.getChildren())) {
                throw new BusinessException("La habitación " + room.getNumber() + " no está activa o no admite a estos huéspedes");
            }
            if (!occupancy.isFree(room.getId(), r.getCheckIn(), r.getCheckOut(), r.getId())) {
                throw new ConflictException("La habitación " + room.getNumber() + " está ocupada en esas fechas");
            }
        }
        boolean overrideUsed = false;
        if (room.getOperationalStatus() != RoomStatus.AVAILABLE) {
            boolean overridable = room.getOperationalStatus() == RoomStatus.CLEANING && req.overrideRoomStatus();
            if (!overridable) {
                throw new BusinessException("La habitación " + room.getNumber() + " está " + statusLabel(room.getOperationalStatus()));
            }
            if (!actor.hasPermission("RESERVATIONS:OVERRIDE")) {
                throw new AccessDeniedException("Solo un supervisor puede asignar una habitación que sigue en limpieza");
            }
            overrideUsed = true;
        }
        if (moved) {
            occupancy.deleteForReservation(r.getId());
            occupancy.insertReservation(hotelId, room.getId(), r.getCheckIn(), r.getCheckOut(), r.getId());
            rr.setRoom(room);
            rr.setRoomType(room.getRoomType());
        }

        Instant now = clock.instant();
        r.setStatus(ReservationStatus.IN_HOUSE);
        r.setCheckedInAt(now);
        r.setCheckedInBy(actor.userId());
        r.setCheckInNotes(blank(req.notes()));
        r.setExpiresAt(null);
        guests.deleteByReservationId(r.getId());
        List<GuestRegistration> list = req.guests() == null || req.guests().isEmpty()
                ? List.of(new GuestRegistration(r.getContactName(), false, null, null)) : req.guests();
        for (GuestRegistration g : list) {
            guests.save(ReservationGuest.builder().reservationId(r.getId()).fullName(g.fullName().trim()).minor(g.minor())
                    .idType(g.idType()).idNumber(blank(g.idNumber())).createdAt(now).build());
        }
        setRoomStatus(room, RoomStatus.OCCUPIED, "Check-in " + r.getFolio(), actor.userId());
        ledger.statusChange(hotelId, r.getId(), "CONFIRMED", "IN_HOUSE",
                "Check-in en la habitación " + room.getNumber() + (moved ? " (cambio desde la " + previous.getNumber() + ")" : ""),
                actor.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("room", room.getNumber());
        after.put("guests", list.size());
        after.put("balance", balance);
        after.put("pendingBalanceAccepted", balance.signum() > 0);
        after.put("roomStillCleaning", overrideUsed);
        auditService.record(AuditEntry.success("RESERVATION_CHECKED_IN", "Reservation", r.getId()).after(after)
                .detail(overrideUsed ? "Excepción autorizada: habitación en limpieza" : null).build());
        guests.flush();
        return queries.detail(r);
    }

    // ── Check-out ──────────────────────────────────────────────────────────────────────

    @Transactional
    public ReservationDetail checkOut(Long id, CheckOutRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        long hotelId = TenantContext.requireHotelId();
        Reservation r = reservations.lockForUpdate(id);
        ReservationStateMachine.requireTransition(r.getStatus(), ReservationStatus.CHECKED_OUT);
        BigDecimal balance = r.balance();
        boolean withBalance = balance.signum() > 0;
        if (withBalance) {
            if (!req.allowPendingBalance()) {
                throw new BusinessException("Saldo pendiente de " + ReservationService.moneyText(balance, r.getCurrency())
                        + ": cóbralo antes de registrar la salida");
            }
            if (!actor.hasPermission("RESERVATIONS:OVERRIDE")) {
                throw new AccessDeniedException("Solo un supervisor puede autorizar una salida con saldo pendiente");
            }
            if (req.reason() == null || req.reason().isBlank()) {
                throw new BusinessException("Indica el motivo para autorizar la salida con saldo pendiente");
            }
        }
        LocalDate today = queries.today();
        boolean early = today.isBefore(r.getCheckOut());
        if (early) {
            LocalDate newEnd = today.isAfter(r.getCheckIn()) ? today : r.getCheckIn().plusDays(1);
            occupancy.shrinkReservation(r.getId(), newEnd);
        }
        Instant now = clock.instant();
        r.setStatus(ReservationStatus.CHECKED_OUT);
        r.setCheckedOutAt(now);
        r.setCheckedOutBy(actor.userId());
        r.setCheckOutNotes(blank(req.notes()));
        Room room = r.getRooms().get(0).getRoom();
        Room locked = rooms.findByIdForUpdate(room.getId()).orElseThrow();
        if (locked.getOperationalStatus() == RoomStatus.OCCUPIED) {
            setRoomStatus(locked, RoomStatus.CLEANING, "Check-out " + r.getFolio(), actor.userId());
        }
        String note = (early ? "Salida anticipada" : "Check-out")
                + (withBalance ? " con saldo pendiente autorizado: " + req.reason().trim() : "");
        ledger.statusChange(hotelId, r.getId(), "IN_HOUSE", "CHECKED_OUT", note, actor.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("room", locked.getNumber());
        after.put("balance", balance);
        after.put("early", early);
        auditService.record(AuditEntry.success("RESERVATION_CHECKED_OUT", "Reservation", r.getId()).after(after)
                .detail(withBalance ? "Excepción autorizada: " + req.reason().trim() : null).build());
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("paid", ReservationService.moneyText(r.getPaidTotal(), r.getCurrency()));
        vars.put("balance", ReservationService.moneyText(balance.max(BigDecimal.ZERO), r.getCurrency()));
        reservations.notifyEvent(r, NotificationEvent.STAY_COMPLETED, "STAY_COMPLETED:" + r.getId(), vars, false);
        return queries.detail(r);
    }

    // ── No-show ────────────────────────────────────────────────────────────────────────

    /**
     * El huésped no llegó. Se puede marcar a partir del día siguiente a la llegada (el mismo día, solo un supervisor).
     * Se cobra la penalización de la política ACEPTADA y se libera la habitación.
     */
    @Transactional
    public ReservationDetail noShow(Long id, NoShowRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = reservations.lockForUpdate(id);
        ReservationStateMachine.requireTransition(r.getStatus(), ReservationStatus.NO_SHOW);
        LocalDate today = queries.today();
        boolean override = actor.hasPermission("RESERVATIONS:OVERRIDE");
        if (today.isBefore(r.getCheckIn()) || (today.isEqual(r.getCheckIn()) && !override)) {
            throw new BusinessException("Se puede marcar como no presentada a partir del día siguiente a la llegada");
        }
        BigDecimal penalty = AccountService.noShowPenalty(r);
        if (req.waivePenalty() && penalty.signum() > 0 && !override) {
            throw new AccessDeniedException("No tienes permiso para condonar la penalización");
        }
        BigDecimal applied = req.waivePenalty() ? BigDecimal.ZERO.setScale(2) : penalty;
        r.setStatus(ReservationStatus.NO_SHOW);
        r.setNoShowAt(clock.instant());
        r.setPenaltyAmount(applied);
        r.setExpiresAt(null);
        occupancy.deleteForReservation(r.getId());
        String reason = req.reason() == null || req.reason().isBlank() ? "El huésped no se presentó" : req.reason().trim();
        ledger.statusChange(TenantContext.requireHotelId(), r.getId(), "CONFIRMED", "NO_SHOW", reason, actor.userId());
        auditService.record(AuditEntry.success("RESERVATION_NO_SHOW", "Reservation", r.getId())
                .after(Map.of("penalty", applied, "waived", req.waivePenalty() && penalty.signum() > 0))
                .detail(reason).build());
        return queries.detail(r);
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private void setRoomStatus(Room room, RoomStatus to, String note, Long userId) {
        RoomStatus from = room.getOperationalStatus();
        if (from == to) {
            return;
        }
        room.setOperationalStatus(to);
        jdbc.update("""
                INSERT INTO housekeeping_logs (hotel_id, room_id, from_status, to_status, notes, user_id)
                VALUES (?, ?, ?, ?, ?, ?)""", TenantContext.requireHotelId(), room.getId(), from.name(), to.name(), note, userId);
    }

    private static RoomOption option(Room room, boolean current) {
        return new RoomOption(room.getId(), room.getNumber(), room.getFloor(), room.getRoomType().getName(),
                room.getOperationalStatus().name(), current);
    }

    private static String statusLabel(RoomStatus s) {
        return switch (s) {
            case AVAILABLE -> "disponible";
            case OCCUPIED -> "ocupada";
            case CLEANING -> "en limpieza";
            case MAINTENANCE -> "en mantenimiento";
            case OUT_OF_SERVICE -> "fuera de servicio";
        };
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
