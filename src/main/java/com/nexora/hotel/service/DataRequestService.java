package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestView;
import com.nexora.hotel.dto.catalog.AccountDtos.ResolveDataRequest;
import com.nexora.hotel.entity.Customer;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.CustomerRepository;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Solicitudes de datos personales (ARCO) que el personal atiende. La eliminación se resuelve ANONIMIZANDO: los
 * registros financieros (reservaciones, pagos, caja, auditoría) se conservan por obligación legal, pero ya no
 * contienen nombre, correo, teléfono ni identificaciones de la persona; su cuenta queda cerrada.
 */
@Service
@RequiredArgsConstructor
public class DataRequestService {

    private final JdbcTemplate jdbc;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final UserNames userNames;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<DataRequestView> list(String status) {
        String filter = status == null || status.isBlank() ? "" : " AND d.status = ?";
        Object[] args = filter.isEmpty() ? new Object[]{TenantContext.requireHotelId()} : new Object[]{TenantContext.requireHotelId(), status};
        List<DataRequestView> rows = jdbc.query("""
                SELECT d.id, d.type, d.status, d.details, d.resolution_notes, d.created_at, d.resolved_at, d.resolved_by,
                       c.id AS customer_id, c.first_name || ' ' || c.last_name AS customer_name, c.email
                FROM data_requests d JOIN customers c ON c.id = d.customer_id
                WHERE d.hotel_id = ?""" + filter + " ORDER BY d.status = 'PENDING' DESC, d.created_at DESC LIMIT 200",
                (rs, i) -> new DataRequestView(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        rs.getString("details"), rs.getString("resolution_notes"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                        rs.getLong("customer_id"), rs.getString("customer_name"), rs.getString("email"),
                        rs.getObject("resolved_by") == null ? null : String.valueOf(rs.getLong("resolved_by"))),
                args);
        Map<Long, String> names = userNames.of(rows.stream().filter(r -> r.resolvedByName() != null)
                .map(r -> Long.valueOf(r.resolvedByName())).toList());
        return rows.stream().map(r -> r.resolvedByName() == null ? r : new DataRequestView(r.id(), r.type(), r.status(),
                r.details(), r.resolutionNotes(), r.createdAt(), r.resolvedAt(), r.customerId(), r.customerName(),
                r.customerEmail(), names.get(Long.valueOf(r.resolvedByName())))).toList();
    }

    @Transactional
    public DataRequestView resolve(Long id, ResolveDataRequest req) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        long hotelId = TenantContext.requireHotelId();
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT customer_id, type, status FROM data_requests WHERE id = ? AND hotel_id = ? FOR UPDATE", id, hotelId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Solicitud no encontrada");
        }
        if (!"PENDING".equals(found.get(0).get("status"))) {
            throw new BusinessException("La solicitud ya fue resuelta");
        }
        long customerId = ((Number) found.get(0).get("customer_id")).longValue();
        boolean anonymize = req.anonymize() && "COMPLETED".equals(req.status());
        if (req.anonymize() && !"DELETION".equals(found.get(0).get("type"))) {
            throw new BusinessException("Solo una solicitud de eliminación puede anonimizar los datos");
        }
        if (anonymize) {
            anonymize(customerId, actor);
        }
        Instant now = clock.instant();
        jdbc.update("""
                UPDATE data_requests SET status = ?, resolution_notes = ?, resolved_by = ?, resolved_at = ? WHERE id = ?""",
                req.status(), req.notes().trim(), actor.userId(), Timestamp.from(now), id);
        auditService.record(AuditEntry.success("DATA_REQUEST_RESOLVED", "Customer", customerId)
                .after(Map.of("requestId", id, "status", req.status(), "anonymized", anonymize)).detail(req.notes().trim()).build());
        return list(null).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
    }

    private void anonymize(long customerId, AuthPrincipal actor) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM reservations WHERE customer_id = ? AND status IN ('PENDING','PENDING_PAYMENT','CONFIRMED','IN_HOUSE')""",
                Integer.class, customerId);
        if (active != null && active > 0) {
            throw new BusinessException("El huésped tiene reservaciones activas: cancélalas o espera a que terminen antes de anonimizar");
        }
        Customer c = customers.findById(customerId).orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
        Instant now = clock.instant();
        String label = "Cliente eliminado #" + customerId;
        c.setFirstName("Cliente");
        c.setLastName("eliminado #" + customerId);
        c.setEmail(null);
        c.setPhone(null);
        c.setPreferences(null);
        c.setNotes(null);
        c.setStatus("BLOCKED");
        c.setAnonymizedAt(now);
        jdbc.update("UPDATE reservations SET contact_name = ?, contact_email = NULL, contact_phone = NULL, special_requests = NULL WHERE customer_id = ?",
                label, customerId);
        jdbc.update("""
                UPDATE reservation_guests SET full_name = ?, id_number_enc = NULL, id_type = NULL
                WHERE reservation_id IN (SELECT id FROM reservations WHERE customer_id = ?)""", label, customerId);
        jdbc.update("DELETE FROM reservation_access_tokens WHERE reservation_id IN (SELECT id FROM reservations WHERE customer_id = ?)", customerId);
        if (c.getUserId() != null) {
            users.findById(c.getUserId()).ifPresent(u -> {
                u.setEmail("eliminado-" + u.getId() + "@anonimo.invalid");
                u.setFirstName("Cuenta");
                u.setLastName("eliminada");
                u.setPhone(null);
                u.setActive(false);
                u.setDeletedAt(now);
                u.setDeletedBy(actor.userId());
                refreshTokens.revokeAllForUser(u.getId());
            });
            c.setUserId(null);
        }
        auditService.record(AuditEntry.success("CUSTOMER_ANONYMIZED", "Customer", customerId).build());
    }
}
