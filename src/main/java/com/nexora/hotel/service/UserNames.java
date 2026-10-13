package com.nexora.hotel.service;

import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Resuelve nombres del personal del hotel actual para mostrar "quién" en pagos, caja y operación. */
@Component
@RequiredArgsConstructor
public class UserNames {

    private final JdbcTemplate jdbc;

    public Map<Long, String> of(Collection<Long> ids) {
        Long[] wanted = ids.stream().filter(Objects::nonNull).distinct().toArray(Long[]::new);
        Map<Long, String> names = new HashMap<>();
        if (wanted.length == 0) {
            return names;
        }
        jdbc.query("SELECT id, first_name || ' ' || last_name AS name FROM users WHERE hotel_id = ? AND id = ANY (?)",
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                }, TenantContext.requireHotelId(), wanted);
        return names;
    }

    public String of(Long id) {
        return id == null ? null : of(java.util.List.of(id)).get(id);
    }
}
