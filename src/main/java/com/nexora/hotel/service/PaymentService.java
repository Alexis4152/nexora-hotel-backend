package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.catalog.PaymentDtos.AddChargeRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentLinkRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentLinkResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStart;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStatus;
import com.nexora.hotel.dto.catalog.PaymentDtos.RecordPaymentRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.RefundRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.ReverseRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.VoidChargeRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.entity.CashSession;
import com.nexora.hotel.entity.ExtraService;
import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.entity.PaymentSettings;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.ReservationCharge;
import com.nexora.hotel.enums.AuditResult;
import com.nexora.hotel.enums.PaymentEnums.CashMovementType;
import com.nexora.hotel.enums.PaymentEnums.Direction;
import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.notification.StaffNotificationType;
import com.nexora.hotel.notification.StaffNotifier;
import com.nexora.hotel.payment.GatewayException;
import com.nexora.hotel.payment.PaymentGateway;
import com.nexora.hotel.payment.PaymentGateway.Charge;
import com.nexora.hotel.payment.PaymentGateway.State;
import com.nexora.hotel.repository.ExtraServiceRepository;
import com.nexora.hotel.repository.PaymentRepository;
import com.nexora.hotel.repository.ReservationChargeRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Libro de pagos de las reservaciones.
 * <ul>
 *   <li>Toda operación que mueve dinero bloquea primero la reservación ({@code FOR UPDATE}) y después el pago: el
 *       neto cobrado ({@code paid_total}) nunca se desfasa ni se cuenta dos veces.</li>
 *   <li>Un pago en línea solo se marca como cobrado con lo que responde la pasarela al consultarla con la llave
 *       privada: ni el regreso del navegador ni el contenido de un webhook bastan.</li>
 *   <li>Las llamadas a la pasarela se hacen FUERA de la transacción (no se retienen bloqueos durante la red): se
 *       registra el intento como PENDIENTE, se llama y se asienta el resultado en una segunda transacción.</li>
 *   <li>Nada se borra: errores de captura se corrigen con un REVERSO; el dinero devuelto, con un REEMBOLSO.</li>
 * </ul>
 */
@Slf4j
@Service
public class PaymentService {

    private static final Duration STAFF_LINK_TTL = Duration.ofHours(48);
    private static final Duration GUEST_LINK_TTL = Duration.ofMinutes(60);

    private final ReservationService reservations;
    private final ReservationQueryService queries;
    private final PaymentRepository payments;
    private final ReservationChargeRepository charges;
    private final ExtraServiceRepository serviceRepository;
    private final CashService cash;
    private final PaymentSettingsService settingsService;
    private final PaymentGateway gateway;
    private final AuditService auditService;
    private final StaffNotifier staffNotifier;
    private final HotelDirectory hotelDirectory;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PaymentService(ReservationService reservations, ReservationQueryService queries, PaymentRepository payments,
                          ReservationChargeRepository charges, ExtraServiceRepository serviceRepository, CashService cash,
                          PaymentSettingsService settingsService, PaymentGateway gateway, AuditService auditService,
                          StaffNotifier staffNotifier, HotelDirectory hotelDirectory, AppProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.reservations = reservations;
        this.queries = queries;
        this.payments = payments;
        this.charges = charges;
        this.serviceRepository = serviceRepository;
        this.cash = cash;
        this.settingsService = settingsService;
        this.gateway = gateway;
        this.auditService = auditService;
        this.staffNotifier = staffNotifier;
        this.hotelDirectory = hotelDirectory;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── Cobro en recepción ─────────────────────────────────────────────────────────────

    /**
     * Efectivo, tarjeta (terminal) o transferencia. El efectivo exige caja abierta; tarjeta y transferencia se ligan a
     * la caja si está abierta (para el corte por método). {@code idempotencyKey} evita el doble cobro por doble clic o
     * reintento: la misma llave devuelve el mismo pago.
     */
    @Transactional
    public ReservationDetail record(Long reservationId, RecordPaymentRequest req, String idempotencyKey) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = reservations.lockForUpdate(reservationId);
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 80) {
            throw new BusinessException("Llave de idempotencia inválida");
        }
        BigDecimal amount = scale(req.amount());
        if (key != null) {
            var previous = payments.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                Payment p = previous.get();
                if (!p.getReservationId().equals(reservationId) || p.getAmount().compareTo(amount) != 0 || p.getMethod() != req.method()) {
                    throw new ConflictException("Esta operación ya se registró con otros datos");
                }
                return queries.detail(r);
            }
        }
        requireMethodEnabled(settingsService.settings(), req.method());
        if (r.getStatus() == ReservationStatus.EXPIRED) {
            throw new BusinessException("La reservación expiró: no admite cobros");
        }
        BigDecimal balance = r.balance();
        if (balance.signum() <= 0) {
            throw new BusinessException("La reservación no tiene saldo pendiente");
        }
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException("El importe excede el saldo pendiente de " + money(balance, r));
        }
        String reference = blank(req.reference());
        if (req.method() == PaymentMethod.TRANSFER && reference == null) {
            throw new BusinessException("Escribe la referencia o clave de rastreo de la transferencia");
        }
        if (req.method() == PaymentMethod.CARD && reference == null && blank(req.cardLast4()) == null) {
            throw new BusinessException("Escribe el número de autorización del voucher o los últimos 4 dígitos");
        }
        CashSession session = req.method() == PaymentMethod.CASH
                ? cash.requireOpenSession(actor.userId(), "cobrar en efectivo")
                : cash.openSessionOf(actor.userId()).orElse(null);
        Instant now = clock.instant();
        Payment p = payments.saveAndFlush(Payment.builder().reservationId(r.getId()).kind(PaymentKind.PAYMENT)
                .method(req.method()).status(PaymentStatus.COMPLETED).amount(amount).currency(r.getCurrency())
                .reference(reference).notes(blank(req.notes())).cardLast4(blank(req.cardLast4()))
                .cashSessionId(session == null ? null : session.getId()).idempotencyKey(key)
                .createdBy(actor.userId()).createdAt(now).completedAt(now).build());
        if (session != null) {
            cash.register(session, p, CashMovementType.PAYMENT, Direction.IN, null);
        }
        applyPaid(r, p.signedAmount());
        auditService.record(AuditEntry.success("PAYMENT_RECORDED", "Reservation", r.getId())
                .after(Map.of("paymentId", p.getId(), "method", p.getMethod().name(), "amount", amount,
                        "balance", r.balance())).build());
        afterCompleted(r, p);
        return queries.detail(r);
    }

    // ── Pago en línea (enlace) ─────────────────────────────────────────────────────────

    /** El personal genera un enlace de pago (por defecto, el saldo) y opcionalmente lo envía por correo. */
    public PaymentLinkResponse createLink(Long reservationId, PaymentLinkRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Payment p = startOnline(reservationId, req.amount(), STAFF_LINK_TTL, actor.userId());
        boolean emailed = false;
        if (req.sendEmail()) {
            emailed = Boolean.TRUE.equals(tx.execute(s -> {
                Reservation r = queries.find(reservationId);
                if (r.getContactEmail() == null) {
                    return false;
                }
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-MX"))
                        .withZone(zone());
                reservations.notifyEvent(r, NotificationEvent.PAYMENT_LINK, "PAYLINK:" + p.getId(),
                        Map.of("amount", money(p.getAmount(), r), "payUrl", p.getCheckoutUrl(), "expires", fmt.format(p.getExpiresAt())),
                        false);
                return true;
            }));
        }
        return new PaymentLinkResponse(p.getId(), p.getCheckoutUrl(), p.getAmount(), p.getExpiresAt(), emailed);
    }

    /** El huésped paga desde su enlace de reservación: el anticipo pendiente o todo el saldo. */
    public PublicPaymentStart startGuest(String folio, String token, String purpose) {
        return startFor(tx.execute(s -> reservations.findByToken(folio, token)), purpose);
    }

    /** Inicia el pago de una reservación ya autorizada (por token o porque es de la cuenta del huésped). */
    PublicPaymentStart startFor(Reservation r, String purpose) {
        BigDecimal amount = "DEPOSIT".equals(purpose) ? ReservationService.depositPending(r) : r.balance();
        if (amount.signum() <= 0) {
            throw new BusinessException("DEPOSIT".equals(purpose) ? "El anticipo ya está cubierto" : "No hay saldo pendiente");
        }
        Payment p = startOnline(r.getId(), amount, GUEST_LINK_TTL, null);
        return new PublicPaymentStart(p.getCheckoutUrl(), p.getAmount(), p.getPublicRef());
    }

    /**
     * Crea el intento de cobro en línea en tres pasos: (1) registra el pago PENDIENTE con un número de orden único;
     * (2) pide a la pasarela el cargo con página de pago; (3) guarda la URL o marca el intento como fallido.
     * Reutiliza un enlace vigente del mismo importe para no generar cargos duplicados.
     */
    private Payment startOnline(Long reservationId, BigDecimal requested, Duration ttl, Long userId) {
        PaymentSettings settings = settingsService.settings();
        if (!settings.onlineUsable()) {
            throw new BusinessException("El pago en línea no está activo en este hotel");
        }
        PaymentGateway.Credentials credentials = settingsService.credentials(settings);
        Payment pending = tx.execute(s -> {
            Reservation r = reservations.lockForUpdate(reservationId);
            if (!(r.getStatus() == ReservationStatus.PENDING || r.getStatus() == ReservationStatus.PENDING_PAYMENT
                    || r.getStatus() == ReservationStatus.CONFIRMED || r.getStatus() == ReservationStatus.IN_HOUSE)) {
                throw new BusinessException("Esta reservación ya no admite pagos en línea");
            }
            BigDecimal amount = scale(requested == null ? r.balance() : requested);
            BigDecimal inFlight = payments.findByReservationIdAndMethodAndStatus(r.getId(), PaymentMethod.ONLINE, PaymentStatus.PENDING)
                    .stream().filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(clock.instant()))
                    .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var reusable = payments.findByReservationIdAndMethodAndStatus(r.getId(), PaymentMethod.ONLINE, PaymentStatus.PENDING)
                    .stream().filter(p -> p.getCheckoutUrl() != null && p.getAmount().compareTo(amount) == 0
                            && p.getExpiresAt() != null && p.getExpiresAt().isAfter(clock.instant().plus(Duration.ofMinutes(10))))
                    .findFirst();
            if (reusable.isPresent()) {
                return reusable.get();
            }
            if (amount.signum() <= 0 || amount.compareTo(r.balance().subtract(inFlight)) > 0) {
                throw new BusinessException(r.balance().signum() <= 0 ? "La reservación no tiene saldo pendiente"
                        : "El importe excede el saldo pendiente de " + money(r.balance().subtract(inFlight).max(BigDecimal.ZERO), r)
                        + (inFlight.signum() > 0 ? " (hay pagos en línea en curso)" : ""));
            }
            Instant now = clock.instant();
            Payment p = payments.saveAndFlush(Payment.builder().reservationId(r.getId()).kind(PaymentKind.PAYMENT)
                    .method(PaymentMethod.ONLINE).status(PaymentStatus.PENDING).amount(amount).currency(r.getCurrency())
                    .provider(gateway.provider()).publicRef(SecureTokens.randomToken()).expiresAt(now.plus(ttl))
                    .createdBy(userId).createdAt(now).build());
            p.setProviderOrderId("NXH" + TenantContext.requireHotelId() + "-" + p.getId());
            return p;
        });
        if (pending.getCheckoutUrl() != null) {
            return pending;
        }
        PaymentGateway.ChargeRequest request = tx.execute(s -> {
            Reservation r = queries.find(reservationId);
            String[] names = splitName(r);
            return new PaymentGateway.ChargeRequest(pending.getProviderOrderId(), pending.getAmount(), pending.getCurrency(),
                    "Reservación " + r.getFolio() + " · " + hotelDirectory.current().getName(),
                    new PaymentGateway.Customer(names[0], names[1], r.getContactEmail(), r.getCustomer() == null ? null : r.getCustomer().getPhone()),
                    returnUrl(pending.getPublicRef()));
        });
        Charge charge;
        try {
            charge = gateway.createRedirectCharge(credentials, request);
        } catch (GatewayException e) {
            tx.executeWithoutResult(s -> {
                Payment p = payments.findByIdForUpdate(pending.getId()).orElseThrow();
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason(truncate(e.getMessage(), 300));
                auditService.record(AuditEntry.of("PAYMENT_LINK_FAILED", AuditResult.FAILED).entityType("Reservation")
                        .entityId(reservationId).detail(e.getMessage()).build());
            });
            throw new BusinessException("No se pudo iniciar el pago en línea: " + e.getMessage());
        }
        Payment saved = tx.execute(s -> {
            Payment p = payments.findByIdForUpdate(pending.getId()).orElseThrow();
            p.setProviderChargeId(charge.id());
            p.setProviderStatus(charge.rawStatus());
            p.setCheckoutUrl(charge.redirectUrl());
            if (charge.redirectUrl() == null) {
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason("La pasarela no devolvió la página de pago");
                return p;
            }
            auditService.record(AuditEntry.success("PAYMENT_LINK_CREATED", "Reservation", reservationId)
                    .after(Map.of("paymentId", p.getId(), "amount", p.getAmount(), "provider", gateway.provider())).build());
            return p;
        });
        if (saved.getCheckoutUrl() == null) {
            throw new BusinessException("La pasarela no devolvió la página de pago. Intenta de nuevo.");
        }
        return saved;
    }

    /**
     * Consulta el cargo en la pasarela y asienta el resultado. Idempotente: si el pago ya no está pendiente solo
     * sincroniza reembolsos hechos desde el panel de la pasarela. Devuelve false si no se pudo consultar.
     */
    public boolean settle(Long paymentId) {
        Payment snapshot = tx.execute(s -> payments.findById(paymentId).orElse(null));
        if (snapshot == null || snapshot.getMethod() != PaymentMethod.ONLINE || snapshot.getKind() != PaymentKind.PAYMENT) {
            return false;
        }
        if (snapshot.getProviderChargeId() == null) {
            if (snapshot.getStatus() == PaymentStatus.PENDING && snapshot.getCreatedAt().isBefore(clock.instant().minus(Duration.ofMinutes(10)))) {
                tx.executeWithoutResult(s -> markUnpaid(snapshot.getReservationId(), paymentId, PaymentStatus.CANCELLED,
                        "No se generó la página de pago"));
            }
            return true;
        }
        Charge charge;
        try {
            charge = gateway.getCharge(settingsService.credentials(settingsService.settings()), snapshot.getProviderChargeId());
        } catch (GatewayException | BusinessException e) {
            log.warn("No se pudo consultar el pago {} en la pasarela: {}", paymentId, e.getMessage());
            return false;
        }
        tx.executeWithoutResult(s -> apply(snapshot.getReservationId(), paymentId, charge));
        return true;
    }

    private void apply(Long reservationId, Long paymentId, Charge charge) {
        Reservation r = reservations.lockForUpdate(reservationId);
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow();
        if (p.getStatus() == PaymentStatus.COMPLETED) {
            syncExternalRefunds(r, p, charge);
            return;
        }
        if (p.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        boolean paid = charge.state() == State.COMPLETED || charge.state() == State.REFUNDED;
        if (paid) {
            boolean orderMatches = charge.orderId() == null || charge.orderId().equals(p.getProviderOrderId());
            boolean amountMatches = charge.amount() != null && charge.amount().compareTo(p.getAmount()) == 0;
            if (!orderMatches || !amountMatches) {
                p.setStatus(PaymentStatus.FAILED);
                p.setProviderStatus(charge.rawStatus());
                p.setFailureReason("La pasarela reportó una orden o un importe distintos: revisa el cargo " + charge.id());
                auditService.record(AuditEntry.of("PAYMENT_MISMATCH", AuditResult.FAILED).entityType("Reservation")
                        .entityId(r.getId()).detail("Pago " + p.getId() + ": orden o importe no coinciden con la pasarela").build());
                notifyFailed(r, p, "MISMATCH", null);
                return;
            }
            Instant now = clock.instant();
            p.setStatus(PaymentStatus.COMPLETED);
            p.setCompletedAt(now);
            p.setProviderStatus(charge.rawStatus());
            p.setCardBrand(truncate(charge.cardBrand(), 30));
            p.setCardLast4(charge.cardLast4());
            applyPaid(r, p.getAmount());
            auditService.record(AuditEntry.success("PAYMENT_ONLINE_COMPLETED", "Reservation", r.getId())
                    .after(Map.of("paymentId", p.getId(), "amount", p.getAmount(), "chargeId", charge.id())).build());
            payments.flush();
            ReservationStatus before = r.getStatus();
            afterCompleted(r, p);
            staffNotifier.notify(reservations.staffNotice(StaffNotificationType.PAYMENT_RECEIVED, r, "PAY_RECEIVED:" + p.getId())
                    .param("amount", p.getAmount().toPlainString()).param("currency", p.getCurrency()).param("method", "ONLINE")
                    .param("confirmed", before == ReservationStatus.PENDING_PAYMENT && r.getStatus() == ReservationStatus.CONFIRMED)
                    .param("roomType", r.getRooms().isEmpty() ? null : r.getRooms().get(0).getRoomType().getName()));
            if (charge.state() == State.REFUNDED) {
                syncExternalRefunds(r, p, charge);
            }
            return;
        }
        if (charge.state() == State.FAILED || charge.state() == State.CANCELLED) {
            markUnpaid(r.getId(), p.getId(), charge.state() == State.FAILED ? PaymentStatus.FAILED : PaymentStatus.CANCELLED,
                    charge.errorMessage() == null ? "El pago no se completó" : charge.errorMessage());
            if (charge.state() == State.FAILED) {
                notifyFailed(r, p, "DECLINED", charge.errorMessage());
            }
            return;
        }
        p.setProviderStatus(charge.rawStatus());
        if (p.getExpiresAt() != null && p.getExpiresAt().isBefore(clock.instant().minus(Duration.ofMinutes(30)))) {
            markUnpaid(r.getId(), p.getId(), PaymentStatus.CANCELLED, "El enlace venció sin completarse el pago");
        }
    }

    private void markUnpaid(Long reservationId, Long paymentId, PaymentStatus status, String reason) {
        Payment p = payments.findByIdForUpdate(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        p.setStatus(status);
        p.setFailureReason(truncate(reason, 300));
        auditService.record(AuditEntry.of("PAYMENT_ONLINE_" + status.name(), AuditResult.FAILED).entityType("Reservation")
                .entityId(reservationId).detail("Pago " + paymentId + ": " + reason).build());
    }

    /** Reembolsos hechos directamente en el panel de la pasarela: se registran para que la cuenta cuadre. */
    private void syncExternalRefunds(Reservation r, Payment p, Charge charge) {
        if (charge.refundedAmount() == null || charge.refundedAmount().signum() <= 0) {
            return;
        }
        BigDecimal recorded = payments.findByRelatedPaymentId(p.getId()).stream()
                .filter(o -> o.getKind() == PaymentKind.REFUND && o.getStatus() != PaymentStatus.FAILED && o.getStatus() != PaymentStatus.CANCELLED)
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal missing = scale(charge.refundedAmount()).subtract(recorded);
        if (missing.signum() <= 0) {
            return;
        }
        Instant now = clock.instant();
        Payment refund = payments.save(Payment.builder().reservationId(r.getId()).kind(PaymentKind.REFUND)
                .method(PaymentMethod.ONLINE).status(PaymentStatus.COMPLETED).amount(missing.min(p.getAmount()))
                .currency(p.getCurrency()).relatedPaymentId(p.getId()).provider(p.getProvider())
                .notes("Reembolso hecho desde el panel de la pasarela").createdAt(now).completedAt(now).build());
        applyPaid(r, refund.signedAmount());
        auditService.record(AuditEntry.success("PAYMENT_REFUND_SYNCED", "Reservation", r.getId())
                .after(Map.of("paymentId", p.getId(), "amount", refund.getAmount())).build());
        staffNotifier.notify(reservations.staffNotice(StaffNotificationType.REFUND_ISSUED, r, "REFUND:" + refund.getId())
                .param("amount", refund.getAmount().toPlainString()).param("currency", refund.getCurrency())
                .param("method", "ONLINE").param("external", true));
    }

    /** Estado de un pago para la página de regreso del huésped. Si sigue pendiente, consulta la pasarela primero. */
    public PublicPaymentStatus publicStatus(String ref) {
        if (ref == null || ref.length() < 20 || ref.length() > 64) {
            throw new ResourceNotFoundException("Pago no encontrado");
        }
        Payment p = tx.execute(s -> payments.findByPublicRef(ref).orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado")));
        if (p.getStatus() == PaymentStatus.PENDING) {
            settle(p.getId());
        }
        return tx.execute(s -> {
            Payment current = payments.findById(p.getId()).orElseThrow();
            Reservation r = queries.find(current.getReservationId());
            return new PublicPaymentStatus(current.getStatus(), current.getAmount(), current.getCurrency(), r.getFolio(),
                    hotelDirectory.current().getName(), current.getFailureReason(), r.getStatus(), settingsService.settings().isSandbox());
        });
    }

    /** El personal pide verificar un pago en línea pendiente con la pasarela. */
    public ReservationDetail verify(Long reservationId, Long paymentId) {
        Payment p = tx.execute(s -> payments.findById(paymentId).filter(x -> x.getReservationId().equals(reservationId))
                .orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado")));
        if (!settle(p.getId())) {
            throw new BusinessException("No se pudo consultar la pasarela en este momento. Intenta de nuevo.");
        }
        return tx.execute(s -> queries.detail(reservationId));
    }

    /** Tarea programada: concilia pagos en línea pendientes (por si el webhook no llegó). */
    public int reconcilePending() {
        List<Long> ids = tx.execute(s -> payments.findByMethodAndStatusAndCreatedAtBefore(PaymentMethod.ONLINE, PaymentStatus.PENDING,
                clock.instant().minus(Duration.ofMinutes(2))).stream().map(Payment::getId).toList());
        int done = 0;
        for (Long id : Objects.requireNonNull(ids)) {
            if (settle(id)) {
                done++;
            }
        }
        return done;
    }

    // ── Reembolsos y reversos ──────────────────────────────────────────────────────────

    /**
     * Devuelve dinero al huésped. Dentro de la política: hasta el saldo a favor (lo cobrado menos lo que se debe).
     * Más allá es un reembolso extraordinario (PAYMENTS:REFUND_EXTRAORDINARY). En línea se devuelve a la tarjeta por
     * la pasarela; en efectivo sale de la caja abierta.
     */
    public ReservationDetail refund(Long reservationId, RefundRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        BigDecimal amount = scale(req.amount());
        String reason = req.reason().trim();
        if (req.method() != PaymentMethod.ONLINE) {
            return tx.execute(s -> {
                Reservation r = reservations.lockForUpdate(reservationId);
                boolean extraordinary = checkRefundable(r, amount, actor);
                String reference = blank(req.reference());
                if (req.method() == PaymentMethod.TRANSFER && reference == null) {
                    throw new BusinessException("Escribe la referencia de la transferencia del reembolso");
                }
                Payment related = req.relatedPaymentId() == null ? null : relatedPayment(r, req.relatedPaymentId(), amount, null);
                CashSession session = req.method() == PaymentMethod.CASH
                        ? cash.requireOpenSession(actor.userId(), "devolver efectivo")
                        : cash.openSessionOf(actor.userId()).orElse(null);
                if (req.method() == PaymentMethod.CASH && cash.expectedCash(session).compareTo(amount) < 0) {
                    throw new BusinessException("No hay suficiente efectivo en tu caja para este reembolso");
                }
                Instant now = clock.instant();
                Payment p = payments.saveAndFlush(Payment.builder().reservationId(r.getId()).kind(PaymentKind.REFUND)
                        .method(req.method()).status(PaymentStatus.COMPLETED).amount(amount).currency(r.getCurrency())
                        .reference(reference).notes(reason).relatedPaymentId(related == null ? null : related.getId())
                        .cashSessionId(session == null ? null : session.getId()).createdBy(actor.userId())
                        .createdAt(now).completedAt(now).build());
                if (session != null) {
                    cash.register(session, p, CashMovementType.REFUND, Direction.OUT, reason);
                }
                applyPaid(r, p.signedAmount());
                refunded(r, p, reason, extraordinary);
                return queries.detail(r);
            });
        }
        // En línea: (1) registrar PENDIENTE, (2) pedir el reembolso a la pasarela, (3) asentar.
        if (req.relatedPaymentId() == null) {
            throw new BusinessException("Elige el pago en línea que se va a reembolsar");
        }
        PaymentGateway.Credentials credentials = settingsService.credentials(settingsService.settings());
        Payment pending = tx.execute(s -> {
            Reservation r = reservations.lockForUpdate(reservationId);
            boolean extraordinary = checkRefundable(r, amount, actor);
            Payment related = relatedPayment(r, req.relatedPaymentId(), amount, PaymentMethod.ONLINE);
            Payment p = payments.saveAndFlush(Payment.builder().reservationId(r.getId()).kind(PaymentKind.REFUND)
                    .method(PaymentMethod.ONLINE).status(PaymentStatus.PENDING).amount(amount).currency(r.getCurrency())
                    .notes(reason + (extraordinary ? " · extraordinario" : "")).relatedPaymentId(related.getId())
                    .provider(related.getProvider()).createdBy(actor.userId()).createdAt(clock.instant()).build());
            return p;
        });
        Payment related = tx.execute(s -> payments.findById(req.relatedPaymentId()).orElseThrow());
        try {
            gateway.refund(credentials, related.getProviderChargeId(), amount, "Reembolso " + reason);
        } catch (GatewayException e) {
            tx.executeWithoutResult(s -> {
                Payment p = payments.findByIdForUpdate(pending.getId()).orElseThrow();
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason(truncate(e.getMessage(), 300));
                auditService.record(AuditEntry.of("PAYMENT_REFUND_FAILED", AuditResult.FAILED).entityType("Reservation")
                        .entityId(reservationId).detail(e.getMessage()).build());
            });
            throw new BusinessException("La pasarela no aplicó el reembolso: " + e.getMessage());
        }
        return tx.execute(s -> {
            Reservation r = reservations.lockForUpdate(reservationId);
            Payment p = payments.findByIdForUpdate(pending.getId()).orElseThrow();
            p.setStatus(PaymentStatus.COMPLETED);
            p.setCompletedAt(clock.instant());
            applyPaid(r, p.signedAmount());
            refunded(r, p, reason, p.getNotes() != null && p.getNotes().endsWith("extraordinario"));
            return queries.detail(r);
        });
    }

    private boolean checkRefundable(Reservation r, BigDecimal amount, AuthPrincipal actor) {
        if (amount.compareTo(r.getPaidTotal()) > 0) {
            throw new BusinessException("No puedes reembolsar más de lo cobrado (" + money(r.getPaidTotal(), r) + ")");
        }
        BigDecimal inPolicy = r.balance().negate().max(BigDecimal.ZERO);
        boolean extraordinary = amount.compareTo(inPolicy) > 0;
        if (extraordinary && !actor.hasPermission("PAYMENTS:REFUND_EXTRAORDINARY")) {
            throw new AccessDeniedException(inPolicy.signum() > 0
                    ? "Según la política solo procede reembolsar " + money(inPolicy, r) + ". Más requiere autorización."
                    : "Esta reservación no tiene saldo a favor: el reembolso requiere autorización");
        }
        return extraordinary;
    }

    private Payment relatedPayment(Reservation r, Long paymentId, BigDecimal amount, PaymentMethod requiredMethod) {
        Payment related = payments.findByIdForUpdate(paymentId)
                .filter(p -> p.getReservationId().equals(r.getId()) && p.getKind() == PaymentKind.PAYMENT
                        && p.getStatus() == PaymentStatus.COMPLETED)
                .orElseThrow(() -> new BusinessException("El pago a reembolsar no es válido"));
        if (requiredMethod != null && related.getMethod() != requiredMethod) {
            throw new BusinessException("Ese pago no se hizo en línea");
        }
        BigDecimal remaining = AccountService.remaining(related, payments.findByReservationIdOrderByCreatedAtAscIdAsc(r.getId()));
        if (amount.compareTo(remaining) > 0) {
            throw new BusinessException("De ese pago solo quedan " + money(remaining, r) + " por reembolsar");
        }
        return related;
    }

    private void refunded(Reservation r, Payment p, String reason, boolean extraordinary) {
        auditService.record(AuditEntry.success("PAYMENT_REFUNDED", "Reservation", r.getId())
                .after(Map.of("paymentId", p.getId(), "method", p.getMethod().name(), "amount", p.getAmount(),
                        "extraordinary", extraordinary))
                .detail(reason).build());
        reservations.notifyEvent(r, NotificationEvent.REFUND_ISSUED, "REFUND:" + p.getId(),
                Map.of("amount", money(p.getAmount(), r), "method", methodLabel(p.getMethod()), "reason", reason), false);
        staffNotifier.notify(reservations.staffNotice(StaffNotificationType.REFUND_ISSUED, r, "REFUND:" + p.getId())
                .param("amount", p.getAmount().toPlainString()).param("currency", p.getCurrency())
                .param("method", p.getMethod().name()).param("reason", reason).param("extraordinary", extraordinary));
    }

    /** Pago en línea rechazado o que no cuadra con la pasarela: el huésped probablemente necesita ayuda. */
    private void notifyFailed(Reservation r, Payment p, String kind, String detail) {
        staffNotifier.notify(reservations.staffNotice(StaffNotificationType.PAYMENT_FAILED, r, "PAY_FAILED:" + p.getId())
                .param("amount", p.getAmount().toPlainString()).param("currency", p.getCurrency())
                .param("kind", kind).param("detail", detail == null ? null : truncate(detail, 200)));
    }

    /**
     * Corrige un cobro presencial capturado por error: crea un REVERSO por el importe completo (el original no se
     * toca). Si fue en efectivo, el dinero sale de la caja abierta de quien reversa.
     */
    @Transactional
    public ReservationDetail reverse(Long reservationId, Long paymentId, ReverseRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = reservations.lockForUpdate(reservationId);
        Payment original = payments.findByIdForUpdate(paymentId)
                .filter(p -> p.getReservationId().equals(reservationId))
                .orElseThrow(() -> new ResourceNotFoundException("Pago no encontrado"));
        if (original.getKind() != PaymentKind.PAYMENT || original.getStatus() != PaymentStatus.COMPLETED) {
            throw new BusinessException("Solo se reversan pagos completados");
        }
        if (!original.getMethod().isPresencial()) {
            throw new BusinessException("Un pago en línea se corrige con un reembolso por la pasarela");
        }
        BigDecimal remaining = AccountService.remaining(original, payments.findByReservationIdOrderByCreatedAtAscIdAsc(reservationId));
        if (remaining.compareTo(original.getAmount()) != 0) {
            throw new BusinessException("Este pago ya tiene reembolsos o reversos registrados");
        }
        String reason = req.reason().trim();
        CashSession session = original.getMethod() == PaymentMethod.CASH
                ? cash.requireOpenSession(actor.userId(), "reversar un cobro en efectivo")
                : cash.openSessionOf(actor.userId()).orElse(null);
        if (original.getMethod() == PaymentMethod.CASH && cash.expectedCash(session).compareTo(original.getAmount()) < 0) {
            throw new BusinessException("No hay suficiente efectivo en tu caja para reversar este cobro");
        }
        Instant now = clock.instant();
        Payment reversal = payments.saveAndFlush(Payment.builder().reservationId(reservationId).kind(PaymentKind.REVERSAL)
                .method(original.getMethod()).status(PaymentStatus.COMPLETED).amount(original.getAmount())
                .currency(original.getCurrency()).relatedPaymentId(original.getId()).notes(reason)
                .cashSessionId(session == null ? null : session.getId()).createdBy(actor.userId())
                .createdAt(now).completedAt(now).build());
        if (session != null) {
            cash.register(session, reversal, CashMovementType.REVERSAL, Direction.OUT, reason);
        }
        applyPaid(r, reversal.signedAmount());
        auditService.record(AuditEntry.success("PAYMENT_REVERSED", "Reservation", reservationId)
                .before(Map.of("paymentId", original.getId(), "amount", original.getAmount(), "method", original.getMethod().name()))
                .after(Map.of("reversalId", reversal.getId())).detail(reason).build());
        return queries.detail(r);
    }

    // ── Cargos a la cuenta ─────────────────────────────────────────────────────────────

    @Transactional
    public ReservationDetail addCharge(Long reservationId, AddChargeRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = reservations.lockForUpdate(reservationId);
        if (!(r.getStatus() == ReservationStatus.CONFIRMED || r.getStatus() == ReservationStatus.IN_HOUSE)) {
            throw new BusinessException("Los cargos se registran en reservaciones confirmadas o en estancia");
        }
        String description = req.description().trim();
        if (req.serviceId() != null) {
            ExtraService svc = serviceRepository.findById(req.serviceId()).filter(ExtraService::isActive)
                    .orElseThrow(() -> new BusinessException("El servicio no está disponible"));
            description = description.isEmpty() ? svc.getName() : description;
        }
        BigDecimal amount = req.quantity().multiply(req.unitPrice()).setScale(2, RoundingMode.HALF_UP);
        ReservationCharge c = charges.save(ReservationCharge.builder().reservationId(r.getId()).serviceId(req.serviceId())
                .description(description).quantity(req.quantity()).unitPrice(scale(req.unitPrice())).amount(amount)
                .createdBy(actor.userId()).createdAt(clock.instant()).build());
        r.setChargesTotal(r.getChargesTotal().add(amount));
        auditService.record(AuditEntry.success("RESERVATION_CHARGE_ADDED", "Reservation", r.getId())
                .after(Map.of("chargeId", c.getId(), "description", description, "amount", amount)).build());
        charges.flush();
        return queries.detail(r);
    }

    /** Anula un cargo (no se borra). Es una corrección financiera: requiere PAYMENTS:REVERSE. */
    @Transactional
    public ReservationDetail voidCharge(Long reservationId, Long chargeId, VoidChargeRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Reservation r = reservations.lockForUpdate(reservationId);
        ReservationCharge c = charges.findById(chargeId).filter(x -> x.getReservationId().equals(reservationId))
                .orElseThrow(() -> new ResourceNotFoundException("Cargo no encontrado"));
        if (c.isVoided()) {
            throw new BusinessException("El cargo ya estaba anulado");
        }
        c.setVoidedAt(clock.instant());
        c.setVoidedBy(actor.userId());
        c.setVoidReason(req.reason().trim());
        r.setChargesTotal(r.getChargesTotal().subtract(c.getAmount()).max(BigDecimal.ZERO));
        auditService.record(AuditEntry.success("RESERVATION_CHARGE_VOIDED", "Reservation", r.getId())
                .before(Map.of("chargeId", c.getId(), "amount", c.getAmount())).detail(req.reason().trim()).build());
        charges.flush();
        return queries.detail(r);
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private void applyPaid(Reservation r, BigDecimal signed) {
        BigDecimal paid = r.getPaidTotal().add(signed);
        if (paid.signum() < 0) {
            throw new BusinessException("La operación dejaría el cobro neto en negativo");
        }
        r.setPaidTotal(paid);
    }

    /** Tras un cobro verificado: confirma si cubre el anticipo y avisa al huésped. */
    private void afterCompleted(Reservation r, Payment p) {
        reservations.confirmByPayment(r);
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("amount", money(p.getAmount(), r));
        vars.put("method", methodLabel(p.getMethod()));
        vars.put("balance", money(r.balance().max(BigDecimal.ZERO), r));
        vars.put("paid", money(r.getPaidTotal(), r));
        reservations.notifyEvent(r, NotificationEvent.PAYMENT_RECEIVED, "PAID:" + p.getId(), vars, true);
    }

    private static void requireMethodEnabled(PaymentSettings s, PaymentMethod method) {
        boolean enabled = switch (method) {
            case CASH -> s.isCashEnabled();
            case CARD -> s.isCardEnabled();
            case TRANSFER -> s.isTransferEnabled();
            case ONLINE -> s.onlineUsable();
        };
        if (!enabled) {
            throw new BusinessException("El método " + methodLabel(method).toLowerCase(Locale.ROOT) + " no está habilitado en este hotel");
        }
    }

    public static String methodLabel(PaymentMethod m) {
        return switch (m) {
            case CASH -> "Efectivo";
            case CARD -> "Tarjeta";
            case TRANSFER -> "Transferencia";
            case ONLINE -> "Pago en línea";
        };
    }

    private String returnUrl(String publicRef) {
        return props.frontendUrl().replaceAll("/+$", "") + "/h/" + hotelDirectory.current().getSlug() + "/pago/" + publicRef;
    }

    private ZoneId zone() {
        return hotelDirectory.current().zoneId();
    }

    private static String[] splitName(Reservation r) {
        if (r.getCustomer() != null) {
            return new String[]{r.getCustomer().getFirstName(), r.getCustomer().getLastName()};
        }
        String[] parts = r.getContactName().split(" ", 2);
        return new String[]{parts[0], parts.length > 1 ? parts[1] : parts[0]};
    }

    private static String money(BigDecimal v, Reservation r) {
        return ReservationService.moneyText(v, r.getCurrency());
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String truncate(String v, int max) {
        return v == null ? null : v.length() <= max ? v : v.substring(0, max);
    }
}
