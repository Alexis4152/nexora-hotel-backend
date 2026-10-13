package com.nexora.hotel.service;

import com.nexora.hotel.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Cuentas de plataforma (SUPER_ADMIN, sin hotel). Se insertan por JDBC porque {@code hotel_id} debe quedar
 * NULL y {@code @TenantId} de Hibernate rellena el tenant actual cuando la entidad no trae valor.
 */
@Service
@RequiredArgsConstructor
public class PlatformAccountService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Long createSuperAdmin(String email, String firstName, String lastName, String rawPassword) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM users WHERE hotel_id IS NULL AND email = ?",
                Integer.class, normalized);
        if (exists != null && exists > 0) {
            throw new ConflictException("Ya existe una cuenta de plataforma con ese correo");
        }
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (hotel_id, email, password_hash, first_name, last_name)
                VALUES (NULL, ?, ?, ?, ?) RETURNING id""", Long.class,
                normalized, passwordEncoder.encode(rawPassword), firstName, lastName);
        jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'SUPER_ADMIN' AND hotel_id IS NULL""", userId);
        return userId;
    }

    public boolean superAdminExists() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
                WHERE r.code = 'SUPER_ADMIN' AND u.hotel_id IS NULL""", Integer.class);
        return count != null && count > 0;
    }
}
