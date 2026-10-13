package com.nexora.hotel.service;

import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.response.AuditLogResponse;
import com.nexora.hotel.dto.response.HotelRef;
import com.nexora.hotel.dto.response.OverviewResponse;
import com.nexora.hotel.entity.AuditLog;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.repository.AuditLogRepository;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/** Resumen del panel y consulta de la bitácora del hotel actual. Solo lectura, sin cifras inventadas. */
@Service
@RequiredArgsConstructor
public class HotelOverviewService {

    private final HotelDirectory hotelDirectory;
    private final AuditLogRepository auditLogRepository;
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    @Transactional(readOnly = true)
    public OverviewResponse overview() {
        long hotelId = TenantContext.requireHotelId();
        Hotel hotel = hotelDirectory.current();

        String staffCondition = """
                u.hotel_id = ? AND u.deleted_at IS NULL AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code <> 'GUEST')""";
        Long active = jdbc.queryForObject("SELECT count(*) FROM users u WHERE u.active AND " + staffCondition, Long.class, hotelId);
        Long inactive = jdbc.queryForObject("SELECT count(*) FROM users u WHERE NOT u.active AND " + staffCondition, Long.class, hotelId);

        List<OverviewResponse.RoleCount> roles = jdbc.query("""
                SELECT r.code, r.name, count(u.id) FROM roles r
                LEFT JOIN user_roles ur ON ur.role_id = r.id
                LEFT JOIN users u ON u.id = ur.user_id AND u.active AND u.deleted_at IS NULL
                WHERE r.hotel_id = ? AND r.active AND r.code <> 'GUEST'
                GROUP BY r.id, r.code, r.name ORDER BY r.system DESC, r.name""",
                (rs, i) -> new OverviewResponse.RoleCount(rs.getString(1), rs.getString(2), rs.getLong(3)), hotelId);

        List<OverviewResponse.RecentLogin> logins = jdbc.query("""
                SELECT u.first_name || ' ' || u.last_name, u.email, u.last_login_at FROM users u
                WHERE u.hotel_id = ? AND u.last_login_at IS NOT NULL ORDER BY u.last_login_at DESC LIMIT 5""",
                (rs, i) -> new OverviewResponse.RecentLogin(rs.getString(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant()), hotelId);

        boolean canSeeAudit = SecurityUtils.currentPrincipal().map(p -> p.hasPermission("AUDIT:VIEW")).orElse(false);
        List<AuditLogResponse> activity = canSeeAudit ? audit(null, null, 0, 8).content() : null;

        java.time.LocalDate today = java.time.LocalDate.now(clock.withZone(hotel.zoneId()));
        OverviewResponse.Operations ops = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM reservations WHERE hotel_id = ? AND check_in = ? AND status IN ('CONFIRMED','PENDING','PENDING_PAYMENT')),
                  (SELECT count(*) FROM reservations WHERE hotel_id = ? AND check_out = ? AND status IN ('CONFIRMED','IN_HOUSE')),
                  (SELECT count(*) FROM reservations WHERE hotel_id = ? AND status = 'IN_HOUSE'),
                  (SELECT count(*) FROM reservations WHERE hotel_id = ? AND status IN ('PENDING','PENDING_PAYMENT')),
                  (SELECT count(DISTINCT o.room_id) FROM room_occupancies o JOIN reservations r ON r.id = o.reservation_id
                     WHERE o.hotel_id = ? AND o.stay @> ?::date AND r.status IN ('CONFIRMED','IN_HOUSE')),
                  (SELECT count(*) FROM rooms WHERE hotel_id = ? AND active),
                  (SELECT count(DISTINCT room_id) FROM room_occupancies WHERE hotel_id = ? AND kind = 'BLOCK' AND stay @> ?::date)""",
                (rs, i) -> new OverviewResponse.Operations(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7)),
                hotelId, today, hotelId, today, hotelId, hotelId, hotelId, today, hotelId, hotelId, today);
        return new OverviewResponse(new HotelRef(hotel.getId(), hotel.getSlug(), hotel.getName(), hotel.getTimezone()),
                active, inactive, roles, logins, activity, ops);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> audit(String action, String actor, int page, int size) {
        Specification<AuditLog> spec = Specification.where(null);
        if (action != null && !action.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action.trim().toUpperCase(Locale.ROOT)));
        }
        if (actor != null && !actor.isBlank()) {
            String like = "%" + actor.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("actorEmail")), like));
        }
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(auditLogRepository.findAll(spec, pageable), HotelOverviewService::toResponse);
    }

    private static AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getCreatedAt(), log.getActorEmail(), log.getAction(),
                log.getEntityType(), log.getEntityId(), log.getResult().name(), log.getDetail(), log.getIp(),
                log.getBeforeData(), log.getAfterData());
    }
}
