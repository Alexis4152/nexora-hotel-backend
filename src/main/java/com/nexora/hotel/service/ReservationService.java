package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.domain.CancellationEvaluator;
import com.nexora.hotel.domain.CancellationEvaluator.Outcome;
import com.nexora.hotel.domain.PricingEngine;
import com.nexora.hotel.domain.ReservationStateMachine;
import com.nexora.hotel.domain.StayPricing;
import com.nexora.hotel.dto.catalog.ReservationDtos.CancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.LookupRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.ModifyRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.NotesRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicCancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationCreated;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationView;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.dto.catalog.ReservationDtos.ServiceSelection;
import com.nexora.hotel.dto.catalog.ReservationDtos.StaffReservationRequest;
import com.nexora.hotel.entity.Customer;
import com.nexora.hotel.entity.ExtraService;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.HotelPolicy;
import com.nexora.hotel.entity.HotelSettings;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.ReservationExtra;
import com.nexora.hotel.entity.ReservationRoom;
import com.nexora.hotel.entity.Room;
import com.nexora.hotel.entity.RoomType;
import com.nexora.hotel.enums.CatalogEnums.RoomStatus;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.repository.PaymentRepository;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.enums.ReservationEnums.ReservationSource;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.notification.NotificationService;
import com.nexora.hotel.notification.StaffNotificationType;
import com.nexora.hotel.notification.StaffNotifier;
import com.nexora.hotel.repository.ExtraServiceRepository;
import com.nexora.hotel.repository.OccupancyRepository;
import com.nexora.hotel.repository.ReservationLedger;
import com.nexora.hotel.repository.ReservationRepository;
import com.nexora.hotel.repository.RoomRepository;
import com.nexora.hotel.repository.RoomTypeRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operaciones sobre reservaciones del hotel actual.
 * <p>
 * Anti doble-reservación (docs/01 §C.3): para ocupar fechas, siempre se bloquea primero la fila de la habitación
 * ({@code SELECT … FOR UPDATE}, en orden de id para evitar interbloqueos), luego se verifica que esté libre y
 * finalmente se inserta la ocupación; la restricción {@code EXCLUDE} de la base es la garantía final.
 * <p>
 * Importes y política aceptada quedan congelados en la reservación; solo un cambio explícito de fechas,
 * habitación o huéspedes recalcula y crea una nueva revisión del desglose.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final String FOLIO_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration ACCESS_TOKEN_EXTRA_LIFE = Duration.ofDays(30);
    private static final List<ReservationStatus> EXPIRABLE = List.of(ReservationStatus.PENDING, ReservationStatus.PENDING_PAYMENT);

    private final ReservationRepository repository;
    private final ReservationQueryService queries;
    private final ReservationLedger ledger;
    private final OccupancyRepository occupancyRepository;
    private final RoomRepository roomRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final ExtraServiceRepository serviceRepository;
    private final CustomerService customerService;
    private final AvailabilityService availabilityService;
    private final RateService rateService;
    private final HotelSettingsService settingsService;
    private final NotificationService notifications;
    private final StaffNotifier staffNotifier;
    private final AuditService auditService;
    private final HotelDirectory hotelDirectory;
    private final PaymentSettingsService paymentSettings;
    private final PaymentRepository paymentRepository;
    private final AppProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    // ── Creación ───────────────────────────────────────────────────────────────────────

    /** @param awaitPayment la reservación queda PENDIENTE DE PAGO hasta que la pasarela verifique el anticipo */
    private record Booking(Customer customer, Long roomTypeId, Long roomId, LocalDate checkIn, LocalDate checkOut,
                           int adults, int children, List<ServiceSelection> services, BigDecimal discount,
                           ReservationSource source, String specialRequests, String internalNotes, boolean confirm,
                           boolean publicChannel, Instant acceptedTermsAt, boolean awaitPayment) {
    }

    private record Created(Reservation reservation, String accessToken) {
    }

    @Transactional
    public ReservationDetail createByStaff(StaffReservationRequest r) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        if (r.confirmNow() && !actor.hasPermission("RESERVATIONS:CONFIRM")) {
            throw new AccessDeniedException("No tienes permiso para confirmar reservaciones");
        }
        if (r.discount() != null && r.discount().signum() > 0 && !actor.hasPermission("RESERVATIONS:APPLY_DISCOUNT")) {
            throw new AccessDeniedException("No tienes permiso para aplicar descuentos");
        }
        Customer customer = r.customerId() != null ? customerService.find(r.customerId()) : customerService.findOrCreate(r.guest(), null);
        if ("BLOCKED".equals(customer.getStatus())) {
            throw new BusinessException("El cliente está bloqueado para nuevas reservaciones");
        }
        Created created = book(new Booking(customer, r.roomTypeId(), r.roomId(), r.checkIn(), r.checkOut(), r.adults(),
                r.children(), r.services(), r.discount(), r.source(), r.specialRequests(), r.internalNotes(),
                r.confirmNow(), false, null, false));
        return queries.detail(created.reservation());
    }

    /**
     * Reservación desde el portal. Si el hotel cobra en línea y su política pide anticipo, queda PENDIENTE DE PAGO y
     * se confirma sola cuando la pasarela verifica el pago; si no, queda PENDIENTE de confirmación (pago en recepción).
     */
    @Transactional
    public PublicReservationCreated createPublic(PublicReservationRequest r) {
        Instant now = clock.instant();
        // Con sesión de huésped, la reservación queda en SU perfil (no en el de quien tenga ese correo escrito).
        Customer customer = SecurityUtils.currentPrincipal()
                .flatMap(p -> customerService.findByUser(p.userId()))
                .orElseGet(() -> customerService.findOrCreate(r.guest(), now));
        if (customer.getPrivacyAcceptedAt() == null) {
            customer.setPrivacyAcceptedAt(now);
        }
        if ("BLOCKED".equals(customer.getStatus())) {
            throw new BusinessException("No es posible completar la reservación en línea. Contáctanos por teléfono.");
        }
        // El huésped puede elegir pagar en el hotel: entonces queda por confirmar (política explícita de pago en recepción).
        boolean awaitPayment = paymentSettings.settings().onlineUsable() && !Boolean.FALSE.equals(r.payOnline())
                && settingsService.currentPolicy().getDepositPercent().signum() > 0;
        Created created = book(new Booking(customer, r.roomTypeId(), null, r.checkIn(), r.checkOut(), r.adults(), r.children(),
                r.services(), BigDecimal.ZERO, ReservationSource.WEB, r.specialRequests(), null, false, true, now, awaitPayment));
        Reservation res = created.reservation();
        return new PublicReservationCreated(res.getFolio(), created.accessToken(), res.getStatus(),
                res.getStatus() == ReservationStatus.PENDING_PAYMENT, res.getDepositRequired());
    }

    private Created book(Booking b) {
        long hotelId = TenantContext.requireHotelId();
        availabilityService.validateStay(b.checkIn(), b.checkOut(), b.adults(), b.children(), b.publicChannel());
        RoomType type = roomTypeRepository.findById(b.roomTypeId()).filter(RoomType::isActive)
                .orElseThrow(() -> new BusinessException("El tipo de habitación no está disponible"));
        requireCapacity(type, b.adults(), b.children());

        StayPricing.Result price = price(type, b.checkIn(), b.checkOut(), b.adults(), b.children(),
                resolveServices(b.services(), b.publicChannel()), b.discount());
        HotelPolicy policy = settingsService.currentPolicy();
        HotelSettings settings = settingsService.settings();
        Instant now = clock.instant();
        BigDecimal deposit = deposit(price.total(), policy.getDepositPercent());
        boolean awaitPayment = b.awaitPayment() && deposit.signum() > 0;
        ReservationStatus status = b.confirm() ? ReservationStatus.CONFIRMED
                : awaitPayment ? ReservationStatus.PENDING_PAYMENT : ReservationStatus.PENDING;
        Instant expires = b.confirm() ? null : awaitPayment
                ? now.plus(Duration.ofMinutes(policy.getPendingPaymentExpiryMinutes()))
                : now.plus(Duration.ofHours(settings.getPendingConfirmationHours()));

        Reservation res = Reservation.builder()
                .folio(newFolio())
                .customer(b.customer())
                .contactName(b.customer().fullName())
                .contactEmail(b.customer().getEmail())
                .contactPhone(b.customer().getPhone() == null ? null : "+" + b.customer().getPhoneCountryCode() + " " + b.customer().getPhone())
                .checkIn(b.checkIn()).checkOut(b.checkOut())
                .nights((int) ChronoUnit.DAYS.between(b.checkIn(), b.checkOut()))
                .adults(b.adults()).children(b.children())
                .status(status).source(b.source())
                .currency(hotelDirectory.current().getCurrency())
                .policyVersionId(policy.getId())
                .policySnapshot(snapshot(policy, settings, price.firstNight()))
                .acceptedTermsAt(b.acceptedTermsAt())
                .specialRequests(blank(b.specialRequests()))
                .internalNotes(blank(b.internalNotes()))
                .confirmedAt(b.confirm() ? now : null)
                .expiresAt(expires)
                .depositRequired(deposit)
                .build();
        applyPrice(res, price);
        res = repository.saveAndFlush(res);

        Room room = assignRoom(hotelId, type, b.roomId(), b.checkIn(), b.checkOut(), res.getId());
        res.getRooms().add(ReservationRoom.builder().reservation(res).room(room).roomType(type)
                .adults(b.adults()).children(b.children()).build());
        ledger.savePriceLines(hotelId, res.getId(), 1, price.lines());
        ledger.statusChange(hotelId, res.getId(), null, status.name(),
                b.publicChannel() ? "Solicitud desde el portal" : "Creada por el personal", actorId());
        auditService.record(AuditEntry.success("RESERVATION_CREATED", "Reservation", res.getId())
                .after(Map.of("folio", res.getFolio(), "room", room.getNumber(), "checkIn", b.checkIn().toString(),
                        "checkOut", b.checkOut().toString(), "total", res.getTotal(), "status", status.name(),
                        "source", b.source().name()))
                .actorEmail(b.publicChannel() ? res.getContactEmail() : null)
                .build());

        String token = res.getContactEmail() == null ? null : issueAccessToken(res);
        notify(res, b.confirm() ? NotificationEvent.RESERVATION_CONFIRMED : NotificationEvent.RESERVATION_CREATED,
                (b.confirm() ? "CONFIRMED:" : "CREATED:") + res.getId(), token, Map.of());
        // Del portal en espera de pago no se avisa todavía: muchas se abandonan. Se avisa cuando el pago se verifica.
        if (status != ReservationStatus.PENDING_PAYMENT) {
            staffNotifier.notify(staffNotice(StaffNotificationType.RESERVATION_CREATED, res, "RES_CREATED:" + res.getId())
                    .param("status", status.name()).param("channel", b.publicChannel() ? "WEB" : "STAFF")
                    .param("roomType", type.getName()));
        }
        return new Created(res, token);
    }

    // ── Cambios de estado ──────────────────────────────────────────────────────────────

    @Transactional
    public ReservationDetail confirm(Long id) {
        Reservation r = lock(id);
        ReservationStatus from = r.getStatus();
        ReservationStateMachine.requireTransition(from, ReservationStatus.CONFIRMED);
        r.setStatus(ReservationStatus.CONFIRMED);
        r.setConfirmedAt(clock.instant());
        r.setExpiresAt(null);
        ledger.statusChange(TenantContext.requireHotelId(), id, from.name(), r.getStatus().name(), null, actorId());
        auditService.record(AuditEntry.success("RESERVATION_CONFIRMED", "Reservation", id)
                .before(Map.of("status", from.name())).after(Map.of("status", "CONFIRMED")).build());
        notify(r, NotificationEvent.RESERVATION_CONFIRMED, "CONFIRMED:" + id, issueAccessTokenIfEmail(r), Map.of());
        staffNotifier.notify(staffNotice(StaffNotificationType.RESERVATION_CONFIRMED, r, "RES_CONFIRMED:" + id));
        return queries.detail(r);
    }

    /**
     * Cancelación por el personal según la política aceptada. Condonar la penalización o cancelar después de la
     * hora de llegada es una excepción: requiere {@code RESERVATIONS:OVERRIDE} y queda en auditoría.
     */
    @Transactional
    public ReservationDetail cancelByStaff(Long id, CancelRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = lock(id);
        ReservationStateMachine.requireTransition(r.getStatus(), ReservationStatus.CANCELLED);
        CancellationEvaluator.Result eval = queries.evaluate(r);
        boolean exception = eval.outcome() == Outcome.REQUIRES_ADMIN || (req.waivePenalty() && eval.penalty().signum() > 0);
        if (exception && !actor.hasPermission("RESERVATIONS:OVERRIDE")) {
            throw new AccessDeniedException(eval.outcome() == Outcome.REQUIRES_ADMIN
                    ? "Ya pasó la hora de llegada: solo un administrador puede cancelar esta reservación"
                    : "No tienes permiso para condonar la penalización");
        }
        BigDecimal penalty = req.waivePenalty() ? BigDecimal.ZERO : eval.penalty();
        cancel(r, req.reason().trim(), penalty, exception ? "Excepción autorizada" : null, false);
        return queries.detail(r);
    }

    private void cancel(Reservation r, String reason, BigDecimal penalty, String note, boolean byGuest) {
        ReservationStatus from = r.getStatus();
        r.setStatus(ReservationStatus.CANCELLED);
        r.setCancelledAt(clock.instant());
        r.setCancelReason(reason);
        r.setPenaltyAmount(penalty.setScale(2, java.math.RoundingMode.HALF_UP));
        r.setExpiresAt(null);
        occupancyRepository.deleteForReservation(r.getId());
        ledger.statusChange(TenantContext.requireHotelId(), r.getId(), from.name(), "CANCELLED",
                note == null ? reason : reason + " · " + note, actorId());
        auditService.record(AuditEntry.success("RESERVATION_CANCELLED", "Reservation", r.getId())
                .before(Map.of("status", from.name()))
                .after(Map.of("status", "CANCELLED", "penalty", r.getPenaltyAmount()))
                .detail(note == null ? reason : reason + " · " + note).build());
        notify(r, NotificationEvent.RESERVATION_CANCELLED, "CANCELLED:" + r.getId(), null,
                Map.of("reason", reason, "penalty", money(r.getPenaltyAmount(), r.getCurrency())));
        staffNotifier.notify(staffNotice(StaffNotificationType.RESERVATION_CANCELLED, r, "RES_CANCELLED:" + r.getId())
                .param("by", byGuest ? "GUEST" : "STAFF").param("reason", reason)
                .param("penalty", r.getPenaltyAmount().signum() > 0 ? r.getPenaltyAmount().toPlainString() : null)
                .param("currency", r.getCurrency()));
    }

    /**
     * Cambio de fechas, tipo, habitación o huéspedes. Se vuelve a verificar disponibilidad (sin chocar consigo
     * misma), se recalcula el precio con las tarifas vigentes y se guarda como nueva revisión del desglose.
     */
    @Transactional
    public ReservationDetail modify(Long id, ModifyRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        long hotelId = TenantContext.requireHotelId();
        Reservation r = lock(id);
        if (!(r.getStatus() == ReservationStatus.PENDING || r.getStatus() == ReservationStatus.PENDING_PAYMENT
                || r.getStatus() == ReservationStatus.CONFIRMED)) {
            throw new BusinessException("Solo se pueden modificar reservaciones pendientes o confirmadas");
        }
        BigDecimal discount = req.discount() == null ? r.getDiscountTotal() : req.discount();
        if (discount.compareTo(r.getDiscountTotal()) != 0 && !actor.hasPermission("RESERVATIONS:APPLY_DISCOUNT")) {
            throw new AccessDeniedException("No tienes permiso para cambiar el descuento");
        }
        availabilityService.validateStay(req.checkIn(), req.checkOut(), req.adults(), req.children(), false);
        RoomType type = roomTypeRepository.findById(req.roomTypeId()).filter(RoomType::isActive)
                .orElseThrow(() -> new BusinessException("El tipo de habitación no está disponible"));
        requireCapacity(type, req.adults(), req.children());

        Map<String, Object> before = describe(r);
        List<StayPricing.ServiceItem> services = r.getServices().stream()
                .map(s -> new StayPricing.ServiceItem(s.getExtraServiceId(), s.getName(), s.getChargeMode(), s.getUnitPrice(), s.getUnits()))
                .toList();
        StayPricing.Result price = price(type, req.checkIn(), req.checkOut(), req.adults(), req.children(), services, discount);

        // Libera sus fechas y vuelve a ocupar: si algo falla, la transacción revierte y conserva las originales.
        Long preferredRoom = req.roomId() != null ? req.roomId()
                : r.getRooms().stream().map(rr -> rr.getRoom()).filter(room -> room.getRoomType().getId().equals(type.getId()))
                .map(Room::getId).findFirst().orElse(null);
        occupancyRepository.deleteForReservation(r.getId());
        Room room = assignRoom(hotelId, type, preferredRoom, req.checkIn(), req.checkOut(), r.getId(), req.roomId() == null);

        r.setCheckIn(req.checkIn());
        r.setCheckOut(req.checkOut());
        r.setNights((int) ChronoUnit.DAYS.between(req.checkIn(), req.checkOut()));
        r.setAdults(req.adults());
        r.setChildren(req.children());
        applyPrice(r, price);
        for (int i = 0; i < r.getServices().size(); i++) {
            r.getServices().get(i).setQuantity(price.services().get(i).quantity());
            r.getServices().get(i).setTotal(price.services().get(i).total());
        }
        r.setPriceRevision(r.getPriceRevision() + 1);
        r.setDepositRequired(deposit(price.total(), new BigDecimal(String.valueOf(r.getPolicySnapshot().getOrDefault("depositPercent", "0")))));
        Map<String, Object> snapshot = new LinkedHashMap<>(r.getPolicySnapshot());
        snapshot.put("firstNight", price.firstNight());
        r.setPolicySnapshot(snapshot);
        r.getRooms().clear();
        r.getRooms().add(ReservationRoom.builder().reservation(r).room(room).roomType(type)
                .adults(req.adults()).children(req.children()).build());
        ledger.savePriceLines(hotelId, r.getId(), r.getPriceRevision(), price.lines());
        ledger.statusChange(hotelId, r.getId(), r.getStatus().name(), r.getStatus().name(), "Modificada: " + req.reason().trim(), actor.userId());
        auditService.record(AuditEntry.success("RESERVATION_MODIFIED", "Reservation", id).before(before).after(describe(r))
                .detail(req.reason().trim()).build());
        notify(r, NotificationEvent.RESERVATION_CHANGED, "CHANGED:" + id + ":" + r.getPriceRevision(),
                issueAccessTokenIfEmail(r), Map.of());
        staffNotifier.notify(staffNotice(StaffNotificationType.RESERVATION_MODIFIED, r, "RES_MODIFIED:" + id + ":" + r.getPriceRevision())
                .param("reason", req.reason().trim()));
        repository.flush();
        return queries.detail(r);
    }

    @Transactional
    public ReservationDetail updateNotes(Long id, NotesRequest req) {
        Reservation r = lock(id);
        r.setSpecialRequests(blank(req.specialRequests()));
        r.setInternalNotes(blank(req.internalNotes()));
        auditService.record(AuditEntry.success("RESERVATION_NOTES_UPDATED", "Reservation", id).build());
        return queries.detail(r);
    }

    @Transactional
    public void resendConfirmation(Long id) {
        Reservation r = queries.find(id);
        if (r.getContactEmail() == null) {
            throw new BusinessException("La reservación no tiene correo de contacto");
        }
        NotificationEvent event = r.getStatus() == ReservationStatus.CONFIRMED ? NotificationEvent.RESERVATION_CONFIRMED
                : r.getStatus() == ReservationStatus.CANCELLED ? NotificationEvent.RESERVATION_CANCELLED
                : NotificationEvent.RESERVATION_CREATED;
        notify(r, event, "RESEND:" + id + ":" + clock.millis(), issueAccessToken(r),
                Map.of("reason", Objects.toString(r.getCancelReason(), ""), "penalty", money(r.getPenaltyAmount(), r.getCurrency())));
        auditService.record(AuditEntry.success("RESERVATION_RESENT", "Reservation", id).build());
    }

    /**
     * Libera solicitudes no confirmadas (o no pagadas) a tiempo; lo invoca la tarea programada por cada hotel.
     * Si hay un pago en línea en curso y su enlace aún no vence, se espera: la conciliación lo resolverá primero.
     */
    @Transactional
    public int expireOverdue() {
        int count = 0;
        Instant now = clock.instant();
        for (Reservation r : repository.findByStatusInAndExpiresAtBefore(EXPIRABLE, now)) {
            boolean paymentInFlight = paymentRepository.findByReservationIdAndMethodAndStatus(r.getId(), PaymentMethod.ONLINE, PaymentStatus.PENDING)
                    .stream().anyMatch(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now));
            if (paymentInFlight) {
                continue;
            }
            ReservationStatus from = r.getStatus();
            r.setStatus(ReservationStatus.EXPIRED);
            r.setExpiresAt(null);
            occupancyRepository.deleteForReservation(r.getId());
            ledger.statusChange(TenantContext.requireHotelId(), r.getId(), from.name(), "EXPIRED",
                    "No se confirmó dentro del plazo", null);
            auditService.record(AuditEntry.of("RESERVATION_EXPIRED", com.nexora.hotel.enums.AuditResult.SUCCESS)
                    .entityType("Reservation").entityId(r.getId()).detail("Liberada automáticamente").build());
            notify(r, NotificationEvent.RESERVATION_CANCELLED, "EXPIRED:" + r.getId(), null,
                    Map.of("reason", "La solicitud no se confirmó dentro del plazo", "penalty", money(BigDecimal.ZERO, r.getCurrency())));
            // Una solicitud que nadie confirmó a tiempo sí se avisa; un pago en línea abandonado no (nunca se avisó que llegó).
            if (from == ReservationStatus.PENDING) {
                staffNotifier.notify(staffNotice(StaffNotificationType.RESERVATION_EXPIRED, r, "RES_EXPIRED:" + r.getId()));
            }
            count++;
        }
        return count;
    }

    // ── Huésped sin cuenta (folio + token) ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PublicReservationView publicView(String folio, String token) {
        return viewOf(byToken(folio, token));
    }

    /** Vista para el huésped (sin notas internas ni datos de otros), ya autorizada por token o por su cuenta. */
    PublicReservationView viewOf(Reservation r) {
        var preview = queries.preview(r);
        boolean canCancel = preview != null && preview.outcome() != Outcome.REQUIRES_ADMIN;
        int revision = r.getPriceRevision();
        return new PublicReservationView(r.getFolio(), r.getStatus(), hotelDirectory.current().getName(), r.getContactName(),
                r.getCheckIn(), r.getCheckOut(), r.getNights(), r.getAdults(), r.getChildren(),
                r.getRooms().isEmpty() ? null : r.getRooms().get(0).getRoomType().getName(), queries.services(r),
                ledger.priceLines(TenantContext.requireHotelId(), r.getId()).stream().filter(l -> l.revision() == revision).toList(),
                r.getTotal(), r.getCurrency(), publicPolicy(r.getPolicySnapshot()), preview, canCancel, r.getExpiresAt(),
                r.getPaidTotal(), r.balance(), depositPending(r), paymentSettings.publicOptions());
    }

    /** Anticipo que falta para cumplir la política (0 si ya se cubrió o la reservación ya no lo admite). */
    public static BigDecimal depositPending(Reservation r) {
        if (!(r.getStatus() == ReservationStatus.PENDING || r.getStatus() == ReservationStatus.PENDING_PAYMENT
                || r.getStatus() == ReservationStatus.CONFIRMED)) {
            return BigDecimal.ZERO;
        }
        return r.getDepositRequired().subtract(r.getPaidTotal()).max(BigDecimal.ZERO);
    }

    @Transactional
    public PublicReservationView cancelByGuest(String folio, String token, PublicCancelRequest req) {
        return cancelAsGuest(byToken(folio, token), req);
    }

    /** Cancelación por el propio huésped (desde su enlace o su cuenta) según la política aceptada. */
    PublicReservationView cancelAsGuest(Reservation found, PublicCancelRequest req) {
        Reservation r = lock(found.getId());
        if (!ReservationStateMachine.canTransition(r.getStatus(), ReservationStatus.CANCELLED)) {
            throw new BusinessException("Esta reservación ya no se puede cancelar en línea");
        }
        CancellationEvaluator.Result eval = queries.evaluate(r);
        if (eval.outcome() == Outcome.REQUIRES_ADMIN) {
            throw new BusinessException("Ya pasó la hora de llegada. Comunícate con el hotel para cancelar.");
        }
        if (eval.penalty().signum() > 0 && !req.acceptPenalty()) {
            throw new BusinessException("Esta cancelación tiene una penalización de " + money(eval.penalty(), r.getCurrency())
                    + ". Confírmala para continuar.");
        }
        String reason = req.reason() == null || req.reason().isBlank() ? "Cancelada por el huésped" : req.reason().trim();
        cancel(r, reason, eval.penalty(), "Desde el portal", true);
        return viewOf(r);
    }

    /** Envía un enlace de acceso al correo de la reservación. Responde igual exista o no (anti-enumeración). */
    @Transactional
    public void sendAccessLink(LookupRequest req) {
        repository.findByFolio(req.folio().trim().toUpperCase(Locale.ROOT))
                .filter(r -> r.getContactEmail() != null && r.getContactEmail().equalsIgnoreCase(req.email().trim()))
                .ifPresent(r -> notify(r, NotificationEvent.RESERVATION_ACCESS_LINK,
                        "LINK:" + r.getId() + ":" + (clock.millis() / 60_000), issueAccessToken(r), Map.of()));
    }

    // ── Usado por pagos y operación (mismo paquete) ────────────────────────────────────

    Reservation lockForUpdate(Long id) {
        return lock(id);
    }

    Reservation findByToken(String folio, String token) {
        return byToken(folio, token);
    }

    /** El anticipo quedó cubierto por un pago verificado: una reservación PENDIENTE DE PAGO pasa a CONFIRMADA. */
    void confirmByPayment(Reservation r) {
        if (r.getStatus() != ReservationStatus.PENDING_PAYMENT || r.getPaidTotal().compareTo(r.getDepositRequired()) < 0) {
            return;
        }
        r.setStatus(ReservationStatus.CONFIRMED);
        r.setConfirmedAt(clock.instant());
        r.setExpiresAt(null);
        ledger.statusChange(TenantContext.requireHotelId(), r.getId(), ReservationStatus.PENDING_PAYMENT.name(), "CONFIRMED",
                "Anticipo verificado", actorId());
        auditService.record(AuditEntry.success("RESERVATION_CONFIRMED", "Reservation", r.getId())
                .before(Map.of("status", "PENDING_PAYMENT")).after(Map.of("status", "CONFIRMED")).detail("Anticipo verificado").build());
        notify(r, NotificationEvent.RESERVATION_CONFIRMED, "CONFIRMED:" + r.getId(), issueAccessTokenIfEmail(r), Map.of());
    }

    /** Correo de un evento de la reservación; {@code withLink} incluye el enlace para que el huésped la consulte. */
    void notifyEvent(Reservation r, NotificationEvent event, String dedup, Map<String, String> extra, boolean withLink) {
        notify(r, event, dedup, withLink ? issueAccessTokenIfEmail(r) : null, extra);
    }

    /** Aviso de la campanita sobre una reservación (folio, huésped y fechas; lleva a su detalle). */
    StaffNotifier.Builder staffNotice(StaffNotificationType type, Reservation r, String dedupKey) {
        return StaffNotifier.Notice.of(type, dedupKey).param("folio", r.getFolio()).param("guest", r.getContactName())
                .param("checkIn", r.getCheckIn()).param("checkOut", r.getCheckOut())
                .link(StaffNotifier.reservationLink(r.getId())).related("Reservation", r.getId());
    }

    static BigDecimal deposit(BigDecimal total, BigDecimal percent) {
        if (percent == null || percent.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return total.multiply(percent).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP).min(total);
    }

    static String moneyText(BigDecimal value, String currency) {
        return money(value, currency);
    }

    private Reservation byToken(String folio, String token) {
        if (folio == null || token == null || token.length() < 20 || token.length() > 100) {
            throw new ResourceNotFoundException("Reservación no encontrada");
        }
        Long id = ledger.reservationForToken(TenantContext.requireHotelId(), folio.trim().toUpperCase(Locale.ROOT),
                SecureTokens.sha256(token)).orElseThrow(() -> new ResourceNotFoundException("Reservación no encontrada"));
        return queries.find(id);
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private Reservation lock(Long id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("Reservación no encontrada"));
    }

    private Room assignRoom(long hotelId, RoomType type, Long requestedRoomId, LocalDate in, LocalDate out, long reservationId) {
        return assignRoom(hotelId, type, requestedRoomId, in, out, reservationId, false);
    }

    /**
     * Asigna y ocupa una habitación. Con {@code requestedRoomId} solo esa (o, si {@code fallback}, esa primero y
     * luego cualquiera del tipo). Bloquea cada candidata antes de verificarla.
     */
    private Room assignRoom(long hotelId, RoomType type, Long requestedRoomId, LocalDate in, LocalDate out, long reservationId,
                            boolean fallback) {
        List<Long> candidates = new ArrayList<>();
        if (requestedRoomId != null) {
            candidates.add(requestedRoomId);
        }
        if (requestedRoomId == null || fallback) {
            occupancyRepository.availableRoomIds(hotelId, type.getId(), in, out, reservationId).stream()
                    .filter(id -> !id.equals(requestedRoomId)).forEach(candidates::add);
        }
        // Orden estable (preferida primero, luego el orden del hotel): operaciones concurrentes del mismo tipo bloquean
        // en la misma secuencia. Si aun así chocaran, PostgreSQL aborta una y el cliente recibe 409 "intenta de nuevo".
        Room chosen = null;
        for (Long id : candidates) {
            Room room = roomRepository.findByIdForUpdate(id).orElse(null);
            if (room == null || !room.isActive() || !room.getRoomType().getId().equals(type.getId())
                    || room.getOperationalStatus() == RoomStatus.OUT_OF_SERVICE) {
                if (requestedRoomId != null && id.equals(requestedRoomId) && !fallback) {
                    throw new BusinessException("La habitación elegida no está disponible o no es del tipo seleccionado");
                }
                continue;
            }
            if (occupancyRepository.isFree(id, in, out, reservationId)) {
                chosen = room;
                break;
            }
            if (requestedRoomId != null && id.equals(requestedRoomId) && !fallback) {
                throw new ConflictException("La habitación " + room.getNumber() + " ya está ocupada en esas fechas");
            }
        }
        if (chosen == null) {
            throw new ConflictException("Ya no hay habitaciones " + type.getName() + " disponibles para esas fechas");
        }
        occupancyRepository.insertReservation(hotelId, chosen.getId(), in, out, reservationId);
        return chosen;
    }

    private StayPricing.Result price(RoomType type, LocalDate in, LocalDate out, int adults, int children,
                                     List<StayPricing.ServiceItem> services, BigDecimal discount) {
        PricingEngine.Quote lodging = rateService.lodging(type, in, out, adults, children);
        try {
            return StayPricing.compute(lodging, adults, children, services, discount, rateService.activeTaxes());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    private List<StayPricing.ServiceItem> resolveServices(List<ServiceSelection> selections, boolean publicChannel) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        Map<Long, ExtraService> catalog = serviceRepository.findAllById(selections.stream().map(ServiceSelection::serviceId).toList())
                .stream().collect(Collectors.toMap(ExtraService::getId, Function.identity()));
        List<StayPricing.ServiceItem> items = new ArrayList<>();
        for (ServiceSelection s : selections) {
            ExtraService svc = catalog.get(s.serviceId());
            if (svc == null || !svc.isActive() || (publicChannel && !svc.isBookableOnline())) {
                throw new BusinessException("Alguno de los servicios ya no está disponible");
            }
            items.add(new StayPricing.ServiceItem(svc.getId(), svc.getName(), svc.getChargeMode(), svc.getPrice(), s.units()));
        }
        return items;
    }

    private void applyPrice(Reservation r, StayPricing.Result price) {
        r.setRoomSubtotal(price.roomSubtotal());
        r.setExtraGuestsTotal(price.extraGuestsTotal());
        r.setServicesTotal(price.servicesTotal());
        r.setDiscountTotal(price.discountTotal());
        r.setTaxTotal(price.taxTotal());
        r.setTotal(price.total());
        if (r.getServices().isEmpty()) {
            for (StayPricing.ChargedService cs : price.services()) {
                r.getServices().add(ReservationExtra.builder().reservation(r).extraServiceId(cs.item().serviceId())
                        .name(cs.item().name()).chargeMode(cs.item().mode()).units(cs.item().units())
                        .quantity(cs.quantity()).unitPrice(cs.item().unitPrice()).total(cs.total()).build());
            }
        }
    }

    private static void requireCapacity(RoomType type, int adults, int children) {
        if (!AvailabilityService.fits(type, adults, children)) {
            throw new BusinessException(type.getName() + " admite hasta " + type.getMaxOccupancy() + " huéspedes ("
                    + type.getMaxAdults() + " adultos, " + type.getMaxChildren() + " menores)");
        }
    }

    /** Copia de la política y horarios ACEPTADOS: la cancelación siempre se evalúa con esto, no con la vigente. */
    private static Map<String, Object> snapshot(HotelPolicy p, HotelSettings s, BigDecimal firstNight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", p.getVersion());
        m.put("freeCancelHours", p.getFreeCancelHours());
        m.put("lateCancelPenaltyType", p.getLateCancelPenaltyType().name());
        m.put("lateCancelPenaltyValue", p.getLateCancelPenaltyValue());
        m.put("noShowPenaltyType", p.getNoShowPenaltyType().name());
        m.put("noShowPenaltyValue", p.getNoShowPenaltyValue());
        m.put("depositPercent", p.getDepositPercent());
        m.put("checkInTime", s.getCheckInTime().toString());
        m.put("checkOutTime", s.getCheckOutTime().toString());
        m.put("petsAllowed", p.isPetsAllowed());
        m.put("refundPolicy", p.getRefundPolicy());
        m.put("childrenPolicy", p.getChildrenPolicy());
        m.put("firstNight", firstNight);
        return m;
    }

    private static Map<String, Object> publicPolicy(Map<String, Object> snapshot) {
        Map<String, Object> m = new LinkedHashMap<>(snapshot);
        m.remove("firstNight");
        return m;
    }

    private String issueAccessTokenIfEmail(Reservation r) {
        return r.getContactEmail() == null ? null : issueAccessToken(r);
    }

    private String issueAccessToken(Reservation r) {
        String raw = SecureTokens.randomToken();
        Instant expires = r.getCheckOut().atStartOfDay(hotelDirectory.current().zoneId()).toInstant().plus(ACCESS_TOKEN_EXTRA_LIFE);
        Instant minimum = clock.instant().plus(Duration.ofDays(7));
        ledger.saveAccessToken(TenantContext.requireHotelId(), r.getId(), SecureTokens.sha256(raw),
                expires.isBefore(minimum) ? minimum : expires);
        return raw;
    }

    private void notify(Reservation r, NotificationEvent event, String dedup, String token, Map<String, String> extra) {
        Hotel hotel = hotelDirectory.current();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Locale.forLanguageTag("es-MX"));
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("guestName", r.getCustomer().getFirstName());
        vars.put("folio", r.getFolio());
        vars.put("checkIn", r.getCheckIn().format(fmt));
        vars.put("checkOut", r.getCheckOut().format(fmt));
        vars.put("nights", String.valueOf(r.getNights()));
        vars.put("guests", r.getAdults() + " adulto(s)" + (r.getChildren() > 0 ? ", " + r.getChildren() + " menor(es)" : ""));
        vars.put("roomType", r.getRooms().isEmpty() ? "" : r.getRooms().get(0).getRoomType().getName());
        vars.put("total", money(r.getTotal(), r.getCurrency()));
        vars.put("status", ReservationStateMachine.label(r.getStatus()));
        vars.put("hotelPhone", Objects.toString(settingsService.publicView().phone(), ""));
        if (token != null) {
            vars.put("manageUrl", props.frontendUrl().replaceAll("/+$", "") + "/h/" + hotel.getSlug()
                    + "/reservacion/" + r.getFolio() + "#" + token);
        }
        vars.putAll(extra);
        notifications.enqueue(event, dedup, r.getContactEmail(), vars, "Reservation", r.getId());
    }

    private Map<String, Object> describe(Reservation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkIn", r.getCheckIn().toString());
        m.put("checkOut", r.getCheckOut().toString());
        m.put("guests", r.getAdults() + "+" + r.getChildren());
        m.put("room", r.getRooms().isEmpty() ? null : r.getRooms().get(0).getRoom().getNumber());
        m.put("total", r.getTotal());
        m.put("discount", r.getDiscountTotal());
        return m;
    }

    private String newFolio() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder("NXH-");
            for (int i = 0; i < 6; i++) {
                sb.append(FOLIO_ALPHABET.charAt(random.nextInt(FOLIO_ALPHABET.length())));
            }
            if (!repository.existsByFolio(sb.toString())) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("No se pudo generar un folio único");
    }

    private static String money(BigDecimal value, String currency) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-MX"));
        f.setCurrency(java.util.Currency.getInstance(currency));
        return f.format(value);
    }

    private static Long actorId() {
        return SecurityUtils.currentPrincipal().map(AuthPrincipal::userId).orElse(null);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
