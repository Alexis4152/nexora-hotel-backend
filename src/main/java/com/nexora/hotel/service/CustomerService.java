package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerDetail;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.CustomerResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.GuestInput;
import com.nexora.hotel.entity.Customer;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.repository.CustomerRepository;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Clientes del hotel actual. Los cambios a datos personales quedan en auditoría (antes/después). El correo es
 * único por hotel: una reservación con un correo ya registrado reutiliza al cliente.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository repository;
    private final ReservationQueryService reservationQueries;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(String query, int page, int size) {
        Specification<Customer> spec = Specification.where(null);
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            String digits = query.replaceAll("\\D", "");
            spec = (root, q, cb) -> cb.or(
                    cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))), like),
                    cb.like(cb.lower(root.get("email")), like),
                    digits.length() >= 4 ? cb.like(root.get("phone"), "%" + digits + "%") : cb.disjunction());
        }
        var result = repository.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by("lastName", "firstName")));
        Map<Long, Object[]> stats = stats(result.getContent().stream().map(Customer::getId).toList());
        return PageResponse.of(result, c -> toDto(c, stats.get(c.getId())));
    }

    @Transactional(readOnly = true)
    public CustomerDetail detail(Long id) {
        Customer c = find(id);
        return new CustomerDetail(toDto(c, stats(List.of(id)).get(id)), reservationQueries.forCustomer(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest r) {
        String email = normalizeEmail(r.guest().email());
        if (email != null && repository.findByEmail(email).isPresent()) {
            throw new ConflictException("Ya existe un cliente con ese correo");
        }
        Customer c = new Customer();
        apply(c, r.guest());
        c.setPreferences(blank(r.preferences()));
        c.setNotes(blank(r.notes()));
        c = repository.save(c);
        auditService.record(AuditEntry.success("CUSTOMER_CREATED", "Customer", c.getId()).after(snapshot(c)).build());
        return toDto(c, null);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest r) {
        Customer c = find(id);
        String email = normalizeEmail(r.guest().email());
        if (email != null && !email.equals(c.getEmail())
                && repository.findByEmail(email).filter(o -> !o.getId().equals(id)).isPresent()) {
            throw new ConflictException("Ya existe un cliente con ese correo");
        }
        Map<String, Object> before = snapshot(c);
        apply(c, r.guest());
        c.setPreferences(blank(r.preferences()));
        c.setNotes(blank(r.notes()));
        repository.save(c);
        Map<String, Object> after = snapshot(c);
        Map<String, Object> changedBefore = new LinkedHashMap<>();
        Map<String, Object> changedAfter = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            if (!Objects.equals(before.get(k), v)) {
                changedBefore.put(k, before.get(k));
                changedAfter.put(k, v);
            }
        });
        if (!changedAfter.isEmpty()) {
            auditService.record(AuditEntry.success("CUSTOMER_UPDATED", "Customer", id).before(changedBefore).after(changedAfter).build());
        }
        return toDto(c, stats(List.of(id)).get(id));
    }

    /** Busca por correo o crea el cliente. Al reutilizarlo solo completa datos vacíos: nunca sobrescribe. */
    @Transactional
    public Customer findOrCreate(GuestInput g, Instant privacyAcceptedAt) {
        String email = normalizeEmail(g.email());
        Customer c = email == null ? null : repository.findByEmail(email).orElse(null);
        if (c == null) {
            c = new Customer();
            apply(c, g);
            c.setPrivacyAcceptedAt(privacyAcceptedAt);
            c = repository.save(c);
            auditService.record(AuditEntry.success("CUSTOMER_CREATED", "Customer", c.getId()).after(snapshot(c)).build());
            return c;
        }
        if (c.getPhone() == null && g.phone() != null && !g.phone().isBlank()) {
            c.setPhone(g.phone().trim());
            c.setPhoneCountryCode(g.phoneCountryCode() == null || g.phoneCountryCode().isBlank() ? "52" : g.phoneCountryCode());
        }
        if (privacyAcceptedAt != null) {
            c.setPrivacyAcceptedAt(privacyAcceptedAt);
        }
        return c;
    }

    /** Perfil de cliente ligado a una cuenta de huésped, si existe. */
    public java.util.Optional<Customer> findByUser(Long userId) {
        return userId == null ? java.util.Optional.empty() : repository.findByUserId(userId);
    }

    public Customer find(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Cliente no encontrado"));
    }

    private static void apply(Customer c, GuestInput g) {
        c.setFirstName(g.firstName().trim());
        c.setLastName(g.lastName().trim());
        c.setEmail(normalizeEmail(g.email()));
        c.setPhoneCountryCode(g.phoneCountryCode() == null || g.phoneCountryCode().isBlank() ? "52" : g.phoneCountryCode());
        c.setPhone(blank(g.phone()));
        c.setCountry(g.country() == null || g.country().isBlank() ? "MX" : g.country());
    }

    private Map<Long, Object[]> stats(List<Long> ids) {
        Map<Long, Object[]> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        String in = String.join(",", ids.stream().map(String::valueOf).toList());
        jdbc.query("SELECT customer_id, count(*), max(check_in) FROM reservations WHERE hotel_id = ? AND customer_id IN (" + in
                + ") AND status NOT IN ('CANCELLED','EXPIRED') GROUP BY customer_id", rs -> {
            map.put(rs.getLong(1), new Object[]{rs.getLong(2), rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate()});
        }, TenantContext.requireHotelId());
        return map;
    }

    private static CustomerResponse toDto(Customer c, Object[] stats) {
        return new CustomerResponse(c.getId(), c.getFirstName(), c.getLastName(), c.getEmail(), c.getPhoneCountryCode(),
                c.getPhone(), c.getCountry(), c.getPreferences(), c.getNotes(), c.getStatus(), c.getUserId() != null,
                stats == null ? 0 : (Long) stats[0], stats == null ? null : (LocalDate) stats[1], c.getCreatedAt());
    }

    private static Map<String, Object> snapshot(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("firstName", c.getFirstName());
        m.put("lastName", c.getLastName());
        m.put("email", c.getEmail());
        m.put("phone", c.getPhone() == null ? null : "+" + c.getPhoneCountryCode() + " " + c.getPhone());
        m.put("country", c.getCountry());
        return m;
    }

    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
