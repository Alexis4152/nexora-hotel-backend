package com.nexora.hotel.mapper;

import com.nexora.hotel.dto.response.RoleRef;
import com.nexora.hotel.dto.response.UserResponse;
import com.nexora.hotel.entity.Role;
import com.nexora.hotel.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;

/** Usuario → DTO. Debe llamarse dentro de la transacción (los roles son LAZY). */
@Component
@RequiredArgsConstructor
public class UserMapper {

    private final Clock clock;

    public UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(), user.getPhone(),
                user.isActive(), user.isMustChangePassword(), user.isLocked(clock.instant()), user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getRoles().stream().sorted(Comparator.comparing(Role::getName)).map(UserMapper::toRef).toList());
    }

    public static RoleRef toRef(Role role) {
        return new RoleRef(role.getId(), role.getCode(), role.getName());
    }
}
