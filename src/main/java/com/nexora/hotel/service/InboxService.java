package com.nexora.hotel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.InboxDtos.NotificationView;
import com.nexora.hotel.dto.catalog.InboxDtos.UnreadCount;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.notification.NotificationStream;
import com.nexora.hotel.notification.StaffNotificationType.Category;
import com.nexora.hotel.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bandeja de avisos de la persona en sesión: cada quien ve y marca solo los suyos. */
@Service
@RequiredArgsConstructor
public class InboxService {

    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final NotificationStream stream;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(boolean unreadOnly, String category, int page, int size) {
        long userId = SecurityUtils.requirePrincipal().userId();
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE user_id = ?");
        List<Object> args = new ArrayList<>(List.of(userId));
        if (unreadOnly) {
            where.append(" AND read_at IS NULL");
        }
        if (category != null && !category.isBlank()) {
            args.add(category(category).name());
            where.append(" AND category = ?");
        }
        long total = Objects.requireNonNullElse(
                jdbc.queryForObject("SELECT count(*) FROM staff_notifications" + where, Long.class, args.toArray()), 0L);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);
        List<NotificationView> content = jdbc.query("""
                SELECT id, type, category, severity, params::text AS params, link, created_at, read_at
                FROM staff_notifications""" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> view(rs), pageArgs.toArray());
        int pages = (int) ((total + safeSize - 1) / safeSize);
        return new PageResponse<>(content, safePage, safeSize, total, pages);
    }

    @Transactional(readOnly = true)
    public UnreadCount unread() {
        return new UnreadCount(countUnread(SecurityUtils.requirePrincipal().userId()));
    }

    @Transactional
    public UnreadCount markRead(Long id) {
        long userId = SecurityUtils.requirePrincipal().userId();
        int found = jdbc.update("UPDATE staff_notifications SET read_at = COALESCE(read_at, ?) WHERE id = ? AND user_id = ?",
                Timestamp.from(clock.instant()), id, userId);
        if (found == 0) {
            throw new ResourceNotFoundException("Notificación no encontrada");
        }
        return synced(userId);
    }

    @Transactional
    public UnreadCount markAllRead() {
        long userId = SecurityUtils.requirePrincipal().userId();
        jdbc.update("UPDATE staff_notifications SET read_at = ? WHERE user_id = ? AND read_at IS NULL",
                Timestamp.from(clock.instant()), userId);
        return synced(userId);
    }

    /** Abre la conexión en vivo; el primer evento trae el contador actual. */
    @Transactional(readOnly = true)
    public SseEmitter subscribe() {
        long userId = SecurityUtils.requirePrincipal().userId();
        return stream.subscribe(userId, countUnread(userId));
    }

    /** Las demás pestañas abiertas de la misma persona actualizan su contador al instante. */
    private UnreadCount synced(long userId) {
        long unread = countUnread(userId);
        stream.publish(userId, "unread", Map.of("unread", unread));
        return new UnreadCount(unread);
    }

    private long countUnread(long userId) {
        return Objects.requireNonNullElse(jdbc.queryForObject(
                "SELECT count(*) FROM staff_notifications WHERE user_id = ? AND read_at IS NULL", Long.class, userId), 0L);
    }

    private NotificationView view(ResultSet rs) throws SQLException {
        Map<String, String> params;
        try {
            params = objectMapper.readValue(rs.getString("params"), PARAMS);
        } catch (Exception e) {
            params = Map.of();
        }
        return new NotificationView(rs.getLong("id"), rs.getString("type"), rs.getString("category"), rs.getString("severity"),
                params, rs.getString("link"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") != null);
    }

    private static Category category(String value) {
        try {
            return Category.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Categoría de notificación inválida");
        }
    }
}
