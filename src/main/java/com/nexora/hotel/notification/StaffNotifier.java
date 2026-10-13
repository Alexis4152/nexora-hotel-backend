package com.nexora.hotel.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.catalog.InboxDtos.NotificationView;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import com.nexora.hotel.repository.UserRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.PermissionResolver;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Crea los avisos de la campanita del panel y los entrega en vivo.
 * <p>
 * Se guardan DENTRO de la transacción de la operación (si ésta se revierte, el aviso tampoco existe) y se empujan
 * por {@link NotificationStream} solo después de confirmarse: nadie recibe un aviso de algo que no pasó.
 * <p>
 * Destinatarios: el personal activo del hotel con el permiso del tipo de aviso, menos quien hizo la acción.
 */
@Service
@RequiredArgsConstructor
public class StaffNotifier {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PermissionResolver permissionResolver;
    private final NotificationStream stream;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Aviso a armar: tipo, valores para redactarlo, a dónde lleva y una clave que evita duplicados. */
    public record Notice(StaffNotificationType type, Map<String, String> params, String link, String relatedType,
                         Long relatedId, String dedupKey) {

        public static Builder of(StaffNotificationType type, String dedupKey) {
            return new Builder(type, dedupKey);
        }
    }

    public static final class Builder {
        private final StaffNotificationType type;
        private final String dedupKey;
        private final Map<String, String> params = new LinkedHashMap<>();
        private String link;
        private String relatedType;
        private Long relatedId;

        private Builder(StaffNotificationType type, String dedupKey) {
            this.type = type;
            this.dedupKey = dedupKey;
        }

        public Builder param(String key, Object value) {
            if (value != null) {
                params.put(key, String.valueOf(value));
            }
            return this;
        }

        public Builder link(String link) {
            this.link = link;
            return this;
        }

        public Builder related(String type, Long id) {
            this.relatedType = type;
            this.relatedId = id;
            return this;
        }

        public Notice build() {
            return new Notice(type, Map.copyOf(params), link, relatedType, relatedId, dedupKey);
        }
    }

    private record Delivery(long userId, NotificationView view, long unread) {
    }

    /** Enlace del panel que abre una reservación. */
    public static String reservationLink(Long id) {
        return "/panel/reservaciones?r=" + id;
    }

    public void notify(Builder builder) {
        notify(builder.build());
    }

    public void notify(Notice notice) {
        long hotelId = TenantContext.requireHotelId();
        Long actorId = SecurityUtils.currentPrincipal().map(AuthPrincipal::userId).orElse(null);
        List<Long> recipients = recipients(hotelId, notice.type().permission()).stream()
                .filter(id -> !id.equals(actorId)).toList();
        if (recipients.isEmpty()) {
            return;
        }
        String params = json(notice.params());
        Instant now = clock.instant();
        List<Delivery> created = new ArrayList<>();
        for (Long userId : recipients) {
            List<Long> ids = jdbc.queryForList("""
                    INSERT INTO staff_notifications (hotel_id, user_id, type, category, severity, params, link, related_type,
                                                     related_id, dedup_key, created_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, dedup_key) DO NOTHING
                    RETURNING id""", Long.class, hotelId, userId, notice.type().name(), notice.type().category().name(),
                    notice.type().severity().name(), params, notice.link(), notice.relatedType(), notice.relatedId(),
                    truncate(notice.dedupKey(), 120), Timestamp.from(now));
            if (!ids.isEmpty() && stream.isConnected(userId)) {
                // El contador se toma aquí (ya incluye este aviso): después del commit no se consulta la base.
                long unread = Objects.requireNonNullElse(jdbc.queryForObject(
                        "SELECT count(*) FROM staff_notifications WHERE user_id = ? AND read_at IS NULL", Long.class, userId), 0L);
                created.add(new Delivery(userId, new NotificationView(ids.get(0), notice.type().name(),
                        notice.type().category().name(), notice.type().severity().name(), notice.params(), notice.link(), now,
                        false), unread));
            }
        }
        afterCommit(() -> created.forEach(d -> stream.publish(d.userId(), "notification",
                Map.of("notification", d.view(), "unread", d.unread()))));
    }

    /** Personal activo del hotel (no huéspedes) que tiene el permiso. */
    private List<Long> recipients(long hotelId, String permission) {
        List<Long> staff = jdbc.queryForList("""
                SELECT DISTINCT u.id FROM users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN roles r ON r.id = ur.role_id AND r.active
                WHERE u.hotel_id = ? AND u.active AND u.deleted_at IS NULL AND r.code <> ?""",
                Long.class, hotelId, Role.GUEST);
        if (staff.isEmpty()) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (User user : users.findAllById(staff)) {
            if (permissionResolver.principalFor(user).hasPermission(permission)) {
                result.add(user.getId());
            }
        }
        result.sort(Long::compare);
        return result;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private String json(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }
}
