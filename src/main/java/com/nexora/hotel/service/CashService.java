package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashAdjustRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashMovementView;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashSessionRow;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashSessionView;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashSummary;
import com.nexora.hotel.dto.catalog.PaymentDtos.CloseCashRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.OpenCashRequest;
import com.nexora.hotel.entity.CashMovement;
import com.nexora.hotel.entity.CashSession;
import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.enums.PaymentEnums.CashMovementType;
import com.nexora.hotel.enums.PaymentEnums.CashSessionStatus;
import com.nexora.hotel.enums.PaymentEnums.Direction;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.CashMovementRepository;
import com.nexora.hotel.repository.CashSessionRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Caja de recepción: cada usuario abre su turno con un fondo inicial, los cobros presenciales quedan ligados a su caja
 * y al cerrar se cuenta el efectivo contra lo esperado. Una caja cerrada no admite movimientos (también lo impide la base).
 * <pre>esperado = fondo + efectivo recibido − efectivo devuelto − retiros + depósitos</pre>
 */
@Service
@RequiredArgsConstructor
public class CashService {

    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final UserNames userNames;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ── Turno propio ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<CashSessionView> current() {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        return sessions.findByUserIdAndStatus(actor.userId(), CashSessionStatus.OPEN).map(this::view);
    }

    @Transactional
    public CashSessionView open(OpenCashRequest r) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        if (sessions.findByUserIdAndStatus(actor.userId(), CashSessionStatus.OPEN).isPresent()) {
            throw new ConflictException("Ya tienes una caja abierta: ciérrala antes de abrir otra");
        }
        CashSession s;
        try {
            s = sessions.saveAndFlush(CashSession.builder().userId(actor.userId()).status(CashSessionStatus.OPEN)
                    .openingFloat(scale(r.openingFloat())).openingNotes(blank(r.notes())).openedAt(clock.instant()).build());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Ya tienes una caja abierta: ciérrala antes de abrir otra");
        }
        auditService.record(AuditEntry.success("CASH_OPENED", "CashSession", s.getId())
                .after(Map.of("openingFloat", s.getOpeningFloat())).build());
        return view(s);
    }

    /** Retiro (ej. envío a bóveda) o depósito de efectivo. Requiere CASH:ADJUST y motivo. */
    @Transactional
    public CashSessionView adjust(Long sessionId, CashAdjustRequest r) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        CashSession s = lockOpen(sessionId, actor);
        BigDecimal amount = scale(r.amount());
        if (r.type() == CashMovementType.WITHDRAWAL && amount.compareTo(summary(s).expectedCash()) > 0) {
            throw new BusinessException("No puedes retirar más efectivo del que hay en la caja");
        }
        movements.save(CashMovement.builder().sessionId(s.getId()).type(r.type()).method(PaymentMethod.CASH)
                .direction(r.type() == CashMovementType.WITHDRAWAL ? Direction.OUT : Direction.IN).amount(amount)
                .reason(r.reason().trim()).userId(actor.userId()).createdAt(clock.instant()).build());
        auditService.record(AuditEntry.success(r.type() == CashMovementType.WITHDRAWAL ? "CASH_WITHDRAWAL" : "CASH_DEPOSIT",
                "CashSession", s.getId()).after(Map.of("amount", amount)).detail(r.reason().trim()).build());
        movements.flush();
        return view(s);
    }

    /**
     * Corte y cierre. Se calcula lo esperado, se registra lo contado y la diferencia. Cerrar la caja de otra persona
     * (ej. un turno olvidado) requiere CASH:VIEW_ALL y CASH:ADJUST.
     */
    @Transactional
    public CashSessionView close(Long sessionId, CloseCashRequest r) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        CashSession s = lockOpen(sessionId, actor);
        CashSummary sum = summary(s);
        BigDecimal counted = scale(r.countedCash());
        BigDecimal difference = counted.subtract(sum.expectedCash());
        if (difference.signum() != 0 && (r.notes() == null || r.notes().isBlank())) {
            throw new BusinessException("Hay una diferencia de " + difference.toPlainString() + ": explica el motivo para cerrar");
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("cashIn", sum.cashIn());
        totals.put("cashOut", sum.cashOut());
        totals.put("cardIn", sum.cardIn());
        totals.put("cardOut", sum.cardOut());
        totals.put("transferIn", sum.transferIn());
        totals.put("transferOut", sum.transferOut());
        totals.put("withdrawals", sum.withdrawals());
        totals.put("deposits", sum.deposits());
        totals.put("movements", sum.movements());
        s.setStatus(CashSessionStatus.CLOSED);
        s.setClosedAt(clock.instant());
        s.setClosedBy(actor.userId());
        s.setExpectedCash(sum.expectedCash());
        s.setCountedCash(counted);
        s.setDifference(difference);
        s.setClosingNotes(blank(r.notes()));
        s.setTotals(totals);
        sessions.flush();
        auditService.record(AuditEntry.success("CASH_CLOSED", "CashSession", s.getId())
                .after(Map.of("expected", sum.expectedCash(), "counted", counted, "difference", difference))
                .detail(blank(r.notes())).build());
        return view(s);
    }

    // ── Consulta ───────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<CashSessionRow> list(int page, int size, String responsible) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
        Page<CashSession> result;
        if (!actor.hasPermission("CASH:VIEW_ALL")) {
            // Sin permiso para ver todas, solo las propias: el filtro por responsable no aplica.
            result = sessions.findByUserIdOrderByOpenedAtDesc(actor.userId(), pr);
        } else if (responsible == null || responsible.isBlank()) {
            result = sessions.findAllByOrderByOpenedAtDesc(pr);
        } else {
            List<Long> userIds = usersNamed(responsible);
            result = userIds.isEmpty() ? Page.empty(pr) : sessions.findByUserIdInOrderByOpenedAtDesc(userIds, pr);
        }
        Map<Long, String> names = userNames.of(result.getContent().stream().map(CashSession::getUserId).toList());
        return PageResponse.of(result, s -> new CashSessionRow(s.getId(), names.get(s.getUserId()), s.getStatus(),
                s.getOpeningFloat(), s.getOpenedAt(), s.getClosedAt(), s.getExpectedCash(), s.getCountedCash(), s.getDifference()));
    }

    /** Usuarios del hotel cuyo nombre o apellido contiene el texto (sin distinguir mayúsculas; % y _ son literales). */
    private List<Long> usersNamed(String text) {
        String needle = text.trim().replaceAll("\\s+", " ");
        needle = needle.substring(0, Math.min(needle.length(), 100)).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.queryForList("""
                SELECT id FROM users
                WHERE hotel_id = ? AND (first_name || ' ' || last_name) ILIKE ? ESCAPE '\\'""",
                Long.class, TenantContext.requireHotelId(), "%" + needle + "%");
    }

    @Transactional(readOnly = true)
    public CashSessionView detail(Long id) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        CashSession s = sessions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caja no encontrada"));
        if (!s.getUserId().equals(actor.userId()) && !actor.hasPermission("CASH:VIEW_ALL")) {
            throw new AccessDeniedException("No puedes ver la caja de otro usuario");
        }
        return view(s);
    }

    // ── Uso desde cobros ───────────────────────────────────────────────────────────────

    /** Caja abierta del usuario, bloqueada para registrar un movimiento. */
    public Optional<CashSession> openSessionOf(Long userId) {
        return sessions.findByUserIdAndStatus(userId, CashSessionStatus.OPEN)
                .flatMap(s -> sessions.findByIdForUpdate(s.getId()))
                .filter(s -> s.getStatus() == CashSessionStatus.OPEN);
    }

    public CashSession requireOpenSession(Long userId, String why) {
        return openSessionOf(userId).orElseThrow(() -> new BusinessException("Abre tu caja para " + why));
    }

    public void register(CashSession s, Payment p, CashMovementType type, Direction direction, String reason) {
        if (s.getStatus() != CashSessionStatus.OPEN) {
            throw new BusinessException("La caja ya está cerrada");
        }
        movements.save(CashMovement.builder().sessionId(s.getId()).type(type).method(p.getMethod()).direction(direction)
                .amount(p.getAmount()).paymentId(p.getId()).reason(reason).userId(p.getCreatedBy())
                .createdAt(clock.instant()).build());
    }

    /** Efectivo disponible ahora en la caja (para validar reembolsos y reversos en efectivo). */
    public BigDecimal expectedCash(CashSession s) {
        return summary(s).expectedCash();
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private CashSession lockOpen(Long sessionId, AuthPrincipal actor) {
        CashSession s = sessions.findByIdForUpdate(sessionId).orElseThrow(() -> new ResourceNotFoundException("Caja no encontrada"));
        if (s.getStatus() != CashSessionStatus.OPEN) {
            throw new BusinessException("La caja ya está cerrada: sus movimientos no se pueden modificar");
        }
        if (!s.getUserId().equals(actor.userId())
                && !(actor.hasPermission("CASH:VIEW_ALL") && actor.hasPermission("CASH:ADJUST"))) {
            throw new AccessDeniedException("Solo el responsable puede operar esta caja");
        }
        return s;
    }

    CashSummary summary(CashSession s) {
        List<CashMovement> list = movements.findBySessionIdOrderByCreatedAtAscIdAsc(s.getId());
        Map<String, BigDecimal> t = new HashMap<>();
        for (CashMovement m : list) {
            String key = switch (m.getType()) {
                case WITHDRAWAL -> "withdrawals";
                case DEPOSIT -> "deposits";
                default -> m.getMethod().name().toLowerCase() + (m.getDirection() == Direction.IN ? "In" : "Out");
            };
            t.merge(key, m.getAmount(), BigDecimal::add);
        }
        BigDecimal cashIn = t.getOrDefault("cashIn", BigDecimal.ZERO);
        BigDecimal cashOut = t.getOrDefault("cashOut", BigDecimal.ZERO);
        BigDecimal withdrawals = t.getOrDefault("withdrawals", BigDecimal.ZERO);
        BigDecimal deposits = t.getOrDefault("deposits", BigDecimal.ZERO);
        BigDecimal expected = s.getOpeningFloat().add(cashIn).subtract(cashOut).subtract(withdrawals).add(deposits);
        return new CashSummary(s.getOpeningFloat(), cashIn, cashOut, t.getOrDefault("cardIn", BigDecimal.ZERO),
                t.getOrDefault("cardOut", BigDecimal.ZERO), t.getOrDefault("transferIn", BigDecimal.ZERO),
                t.getOrDefault("transferOut", BigDecimal.ZERO), withdrawals, deposits, scale(expected), list.size());
    }

    private CashSessionView view(CashSession s) {
        List<CashMovement> list = movements.findBySessionIdOrderByCreatedAtAscIdAsc(s.getId());
        List<Long> userIds = new ArrayList<>(list.stream().map(CashMovement::getUserId).toList());
        userIds.add(s.getUserId());
        userIds.add(s.getClosedBy());
        Map<Long, String> names = userNames.of(userIds);
        Map<Long, String[]> folios = folios(list);
        List<CashMovementView> views = list.stream().map(m -> {
            String[] f = m.getPaymentId() == null ? null : folios.get(m.getPaymentId());
            return new CashMovementView(m.getId(), m.getType(), m.getMethod(), m.getDirection(), m.getAmount(), m.getPaymentId(),
                    f == null ? null : Long.valueOf(f[0]), f == null ? null : f[1], m.getReason(), names.get(m.getUserId()),
                    m.getCreatedAt());
        }).toList();
        return new CashSessionView(s.getId(), s.getUserId(), names.get(s.getUserId()), s.getStatus(), s.getOpeningFloat(),
                s.getOpeningNotes(), s.getOpenedAt(), s.getClosedAt(), names.get(s.getClosedBy()), s.getExpectedCash(),
                s.getCountedCash(), s.getDifference(), s.getClosingNotes(), summary(s), views);
    }

    /** paymentId → [reservationId, folio] para mostrar a qué reservación corresponde cada cobro. */
    private Map<Long, String[]> folios(List<CashMovement> list) {
        Long[] ids = list.stream().map(CashMovement::getPaymentId).filter(java.util.Objects::nonNull).toArray(Long[]::new);
        Map<Long, String[]> out = new HashMap<>();
        if (ids.length > 0) {
            jdbc.query("""
                    SELECT p.id, r.id AS reservation_id, r.folio FROM payments p JOIN reservations r ON r.id = p.reservation_id
                    WHERE p.hotel_id = ? AND p.id = ANY (?)""",
                    rs -> {
                        out.put(rs.getLong("id"), new String[]{String.valueOf(rs.getLong("reservation_id")), rs.getString("folio")});
                    }, TenantContext.requireHotelId(), ids);
        }
        return out;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
