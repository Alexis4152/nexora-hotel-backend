package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.catalog.AccountDtos.AccountPayment;
import com.nexora.hotel.dto.catalog.AccountDtos.AccountReservation;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestCreate;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestView;
import com.nexora.hotel.dto.catalog.AccountDtos.Profile;
import com.nexora.hotel.dto.catalog.AccountDtos.ProfileRequest;
import com.nexora.hotel.dto.catalog.AccountDtos.RegisterRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStart;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicCancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationView;
import com.nexora.hotel.entity.Customer;
import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.RoomImage;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.notification.NotificationService;
import com.nexora.hotel.notification.StaffNotificationType;
import com.nexora.hotel.notification.StaffNotifier;
import com.nexora.hotel.repository.CustomerRepository;
import com.nexora.hotel.repository.PaymentRepository;
import com.nexora.hotel.repository.ReservationRepository;
import com.nexora.hotel.repository.RoleRepository;
import com.nexora.hotel.repository.RoomImageRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.storage.MediaUrls;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cuenta del huésped en el portal del hotel.
 * <ul>
 *   <li>Registro con verificación de correo: hasta confirmar el enlace no se puede iniciar sesión ni se liga a
 *       reservaciones existentes. Así, registrar el correo de otra persona no da acceso a sus estancias.</li>
 *   <li>Las respuestas de registro y reenvío son iguales exista o no la cuenta (anti-enumeración).</li>
 *   <li>Cada operación sobre una reservación verifica que pertenezca al cliente de la cuenta en sesión.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GuestAccountService {

    private static final Duration VERIFY_TTL = Duration.ofHours(24);
    private static final Map<String, String> REQUEST_LABEL = Map.of("ACCESS", "acceso", "RECTIFICATION", "rectificación",
            "DELETION", "eliminación", "OPPOSITION", "oposición al uso");

    private final UserRepository users;
    private final RoleRepository roles;
    private final CustomerRepository customers;
    private final CustomerService customerService;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ReservationQueryService queries;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final RoomImageRepository images;
    private final MediaUrls mediaUrls;
    private final NotificationService notifications;
    private final StaffNotifier staffNotifier;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final HotelDirectory hotelDirectory;
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ── Registro y verificación ────────────────────────────────────────────────────────

    @Transactional
    public void register(RegisterRequest r) {
        long hotelId = TenantContext.requireHotelId();
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        User existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            // Misma respuesta que un registro nuevo. Si la cuenta aún no se verificó, se reenvía el enlace.
            if (existing.getEmailVerifiedAt() == null && existing.getDeletedAt() == null) {
                sendVerification(existing);
            }
            return;
        }
        User user = users.save(User.builder().email(email).firstName(r.firstName().trim()).lastName(r.lastName().trim())
                .phone(blank(r.phone())).phoneCountryCode(blank(r.phoneCountryCode())).passwordHash(passwordEncoder.encode(r.password())).emailVerifiedAt(null)
                .roles(new HashSet<>(Set.of(roles.findByCode(com.nexora.hotel.entity.Role.GUEST)
                        .orElseThrow(() -> new IllegalStateException("El hotel no tiene rol de huésped")))))
                .build());
        auditService.record(AuditEntry.success("GUEST_REGISTERED", "User", user.getId())
                .actorUserId(user.getId()).actorEmail(email).hotelId(hotelId).build());
        sendVerification(user);
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        users.findByEmail(rawEmail.trim().toLowerCase(Locale.ROOT))
                .filter(u -> u.getEmailVerifiedAt() == null && u.getDeletedAt() == null)
                .ifPresent(this::sendVerification);
    }

    /**
     * Confirma el correo. Liga la cuenta al perfil de cliente con ese correo (sus reservaciones previas aparecen en
     * "Mis reservaciones") o crea uno nuevo.
     */
    @Transactional
    public void verify(String rawToken) {
        long hotelId = TenantContext.requireHotelId();
        Instant now = clock.instant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, user_id FROM email_verification_tokens
                WHERE token_hash = ? AND hotel_id = ? AND used_at IS NULL AND expires_at > ? FOR UPDATE""",
                SecureTokens.sha256(rawToken.trim()), hotelId, Timestamp.from(now));
        if (rows.isEmpty()) {
            throw new BusinessException("El enlace no es válido o ya venció. Pide uno nuevo desde \"Iniciar sesión\".");
        }
        long userId = ((Number) rows.get(0).get("user_id")).longValue();
        jdbc.update("UPDATE email_verification_tokens SET used_at = ? WHERE id = ?", Timestamp.from(now),
                ((Number) rows.get(0).get("id")).longValue());
        User user = users.findById(userId).orElseThrow(() -> new BusinessException("La cuenta ya no existe"));
        if (user.getEmailVerifiedAt() != null) {
            return;
        }
        user.setEmailVerifiedAt(now);
        Customer customer = customers.findByEmail(user.getEmail()).orElse(null);
        if (customer != null && customer.getUserId() != null && !customer.getUserId().equals(userId)) {
            throw new BusinessException("Ese correo ya está ligado a otra cuenta. Contacta al hotel.");
        }
        if (customer == null) {
            customer = new Customer();
            customer.setFirstName(user.getFirstName());
            customer.setLastName(user.getLastName());
            customer.setEmail(user.getEmail());
            customer.setPhone(user.getPhone());
            String code = user.getPhoneCountryCode();
            customer.setPhoneCountryCode(code == null || code.isBlank() ? "52" : code);
        }
        customer.setUserId(userId);
        if (customer.getPrivacyAcceptedAt() == null) {
            customer.setPrivacyAcceptedAt(now);
        }
        customers.save(customer);
        auditService.record(AuditEntry.success("GUEST_EMAIL_VERIFIED", "User", userId)
                .actorUserId(userId).actorEmail(user.getEmail()).hotelId(hotelId)
                .after(Map.of("customerId", customer.getId())).build());
    }

    private void sendVerification(User user) {
        Instant now = clock.instant();
        String raw = SecureTokens.randomToken();
        jdbc.update("UPDATE email_verification_tokens SET used_at = ? WHERE user_id = ? AND used_at IS NULL",
                Timestamp.from(now), user.getId());
        jdbc.update("INSERT INTO email_verification_tokens (hotel_id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                TenantContext.requireHotelId(), user.getId(), SecureTokens.sha256(raw), Timestamp.from(now.plus(VERIFY_TTL)));
        String url = props.frontendUrl().replaceAll("/+$", "") + "/h/" + hotelDirectory.current().getSlug() + "/cuenta/verificar/" + raw;
        notifications.enqueue(NotificationEvent.ACCOUNT_VERIFY, "VERIFY:" + user.getId() + ":" + now.toEpochMilli(),
                user.getEmail(), Map.of("guestName", user.getFirstName(), "verifyUrl", url), "User", user.getId());
    }

    // ── Perfil ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Profile profile() {
        User user = currentUser();
        Customer c = myCustomer();
        long stays = reservationRepository.findByCustomerIdOrderByCheckInDesc(c.getId()).stream()
                .filter(r -> r.getStatus() == ReservationStatus.CHECKED_OUT).count();
        return new Profile(c.getFirstName(), c.getLastName(), user.getEmail(), c.getPhoneCountryCode(), c.getPhone(),
                c.getCountry(), user.getCreatedAt(), stays, requestsOf(c.getId()));
    }

    @Transactional
    public Profile updateProfile(ProfileRequest r) {
        User user = currentUser();
        Customer c = myCustomer();
        Map<String, Object> before = Map.of("name", c.fullName(), "phone", String.valueOf(c.getPhone()));
        user.setFirstName(r.firstName().trim());
        user.setLastName(r.lastName().trim());
        user.setPhone(blank(r.phone()));
        c.setFirstName(r.firstName().trim());
        c.setLastName(r.lastName().trim());
        c.setPhone(blank(r.phone()));
        c.setPhoneCountryCode(r.phoneCountryCode() == null || r.phoneCountryCode().isBlank() ? "52" : r.phoneCountryCode());
        c.setCountry(r.country() == null || r.country().isBlank() ? "MX" : r.country());
        auditService.record(AuditEntry.success("GUEST_PROFILE_UPDATED", "Customer", c.getId()).before(before)
                .after(Map.of("name", c.fullName(), "phone", String.valueOf(c.getPhone()))).build());
        return profile();
    }

    // ── Reservaciones y pagos ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AccountReservation> reservations() {
        Customer c = myCustomer();
        List<Reservation> list = reservationRepository.findByCustomerIdOrderByCheckInDesc(c.getId());
        Map<Long, String> covers = covers(list);
        LocalDate today = queries.today();
        return list.stream().map(r -> {
            var room = r.getRooms().isEmpty() ? null : r.getRooms().get(0);
            boolean upcoming = !r.getCheckOut().isBefore(today) && !r.getStatus().isFinal();
            return new AccountReservation(r.getFolio(), r.getStatus(), r.getCheckIn(), r.getCheckOut(), r.getNights(),
                    r.getAdults(), r.getChildren(), room == null ? null : room.getRoomType().getName(),
                    room == null ? null : covers.get(room.getRoomType().getId()), r.getTotal(), r.getPaidTotal(), r.balance(),
                    ReservationService.depositPending(r), upcoming);
        }).toList();
    }

    @Transactional(readOnly = true)
    public PublicReservationView reservation(String folio) {
        return reservationService.viewOf(owned(folio));
    }

    @Transactional
    public PublicReservationView cancel(String folio, PublicCancelRequest req) {
        return reservationService.cancelAsGuest(owned(folio), req);
    }

    public PublicPaymentStart pay(String folio, String purpose) {
        return paymentService.startFor(owned(folio), purpose);
    }

    /** Detalle para el comprobante PDF (solo se usa para generarlo: no se devuelve en JSON). */
    @Transactional(readOnly = true)
    public com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail receiptDetail(String folio) {
        return queries.detail(owned(folio));
    }

    @Transactional(readOnly = true)
    public List<AccountPayment> payments() {
        Customer c = myCustomer();
        Map<Long, Reservation> mine = reservationRepository.findByCustomerIdOrderByCheckInDesc(c.getId()).stream()
                .collect(Collectors.toMap(Reservation::getId, r -> r));
        return mine.keySet().stream().flatMap(id -> payments.findByReservationIdOrderByCreatedAtAscIdAsc(id).stream())
                .filter(p -> p.getStatus() != com.nexora.hotel.enums.PaymentEnums.PaymentStatus.FAILED
                        && p.getStatus() != com.nexora.hotel.enums.PaymentEnums.PaymentStatus.CANCELLED)
                .sorted(java.util.Comparator.comparing(Payment::getCreatedAt).reversed())
                .map(p -> new AccountPayment(p.getId(), mine.get(p.getReservationId()).getFolio(), p.getKind(), p.getMethod(),
                        p.getStatus(), p.getAmount(), p.getCurrency(), p.getCardLast4(), p.getCreatedAt(), p.getCompletedAt()))
                .toList();
    }

    // ── Datos personales (ARCO) ────────────────────────────────────────────────────────

    /** Copia de los datos personales del huésped (derecho de acceso), descargable en JSON. */
    @Transactional
    public Map<String, Object> export() {
        User user = currentUser();
        Customer c = myCustomer();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generadoEl", clock.instant().toString());
        out.put("hotel", hotelDirectory.current().getName());
        out.put("cuenta", Map.of("correo", user.getEmail(), "creada", String.valueOf(user.getCreatedAt())));
        Map<String, Object> perfil = new LinkedHashMap<>();
        perfil.put("nombre", c.getFirstName());
        perfil.put("apellidos", c.getLastName());
        perfil.put("telefono", c.getPhone() == null ? null : "+" + c.getPhoneCountryCode() + " " + c.getPhone());
        perfil.put("pais", c.getCountry());
        perfil.put("avisoDePrivacidadAceptado", String.valueOf(c.getPrivacyAcceptedAt()));
        out.put("perfil", perfil);
        out.put("reservaciones", reservations().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("folio", r.folio());
            m.put("estado", r.status().name());
            m.put("llegada", r.checkIn().toString());
            m.put("salida", r.checkOut().toString());
            m.put("habitacion", r.roomTypeName());
            m.put("total", r.total());
            m.put("pagado", r.paid());
            return m;
        }).toList());
        out.put("pagos", payments().stream().map(p -> Map.of("folio", p.folio(), "tipo", p.kind().name(),
                "metodo", p.method().name(), "importe", p.amount(), "fecha", String.valueOf(p.createdAt()))).toList());
        auditService.record(AuditEntry.success("GUEST_DATA_EXPORTED", "Customer", c.getId()).build());
        return out;
    }

    @Transactional
    public DataRequestView requestData(DataRequestCreate r) {
        User user = currentUser();
        Customer c = myCustomer();
        Integer open = jdbc.queryForObject("SELECT count(*) FROM data_requests WHERE customer_id = ? AND type = ? AND status = 'PENDING'",
                Integer.class, c.getId(), r.type());
        if (open != null && open > 0) {
            throw new BusinessException("Ya tienes una solicitud de ese tipo en trámite");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO data_requests (hotel_id, customer_id, user_id, type, details) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, TenantContext.requireHotelId(), c.getId(), user.getId(), r.type(), blank(r.details()));
        auditService.record(AuditEntry.success("DATA_REQUEST_CREATED", "Customer", c.getId())
                .after(Map.of("requestId", id, "type", r.type())).build());
        notifications.enqueue(NotificationEvent.DATA_REQUEST_RECEIVED, "DATAREQ:" + id, null,
                Map.of("guestName", c.fullName(), "reason", REQUEST_LABEL.get(r.type())), "Customer", c.getId());
        staffNotifier.notify(StaffNotifier.Notice.of(StaffNotificationType.DATA_REQUEST_CREATED, "DATAREQ:" + id)
                .param("guest", c.fullName()).param("requestType", r.type())
                .link("/panel/clientes?tab=solicitudes").related("Customer", c.getId()));
        return requestsOf(c.getId()).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private User currentUser() {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        return users.findById(p.userId()).orElseThrow(() -> new ResourceNotFoundException("Cuenta no encontrada"));
    }

    /** Perfil de cliente de la cuenta en sesión. El personal sin perfil de huésped no tiene "Mi cuenta". */
    private Customer myCustomer() {
        AuthPrincipal p = SecurityUtils.requirePrincipal();
        return customerService.findByUser(p.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Tu cuenta no tiene un perfil de huésped en este hotel"));
    }

    /** La reservación con ese folio, solo si es del cliente en sesión (si no, "no encontrada": no se revela). */
    private Reservation owned(String folio) {
        Customer c = myCustomer();
        return reservationRepository.findByFolio(folio == null ? "" : folio.trim().toUpperCase(Locale.ROOT))
                .filter(r -> r.getCustomer().getId().equals(c.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Reservación no encontrada"));
    }

    private Map<Long, String> covers(List<Reservation> list) {
        List<Long> typeIds = list.stream().filter(r -> !r.getRooms().isEmpty())
                .map(r -> r.getRooms().get(0).getRoomType().getId()).distinct().toList();
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        return images.findByRoomTypeIdInOrderBySortOrderAscIdAsc(typeIds).stream()
                .collect(Collectors.toMap(RoomImage::getRoomTypeId, i -> mediaUrls.of(i.getFile()), (a, b) -> a));
    }

    List<DataRequestView> requestsOf(Long customerId) {
        return jdbc.query("""
                SELECT d.id, d.type, d.status, d.details, d.resolution_notes, d.created_at, d.resolved_at, d.customer_id
                FROM data_requests d WHERE d.customer_id = ? AND d.hotel_id = ? ORDER BY d.created_at DESC""",
                (rs, i) -> new DataRequestView(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        rs.getString("details"), rs.getString("resolution_notes"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getLong("customer_id"), null, null, null),
                customerId, TenantContext.requireHotelId());
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
