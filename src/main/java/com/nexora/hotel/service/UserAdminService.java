package com.nexora.hotel.service;

import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.request.CreateUserRequest;
import com.nexora.hotel.dto.request.UpdateUserRequest;
import com.nexora.hotel.dto.request.UserOverridesRequest;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;
import com.nexora.hotel.dto.response.UserDetailResponse;
import com.nexora.hotel.dto.response.UserResponse;

/** Personal del hotel actual. Todas las operaciones quedan acotadas al hotel del usuario autenticado. */
public interface UserAdminService {

    PageResponse<UserResponse> list(String query, Boolean active, int page, int size);

    UserDetailResponse get(Long id);

    /** Crea al usuario con contraseña temporal (se devuelve una sola vez) y cambio obligatorio al entrar. */
    TemporaryPasswordResponse create(CreateUserRequest request);

    UserResponse update(Long id, UpdateUserRequest request);

    UserResponse setActive(Long id, boolean active);

    TemporaryPasswordResponse resetPassword(Long id);

    UserDetailResponse setOverrides(Long id, UserOverridesRequest request);
}
