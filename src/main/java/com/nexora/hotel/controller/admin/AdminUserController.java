package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.request.CreateUserRequest;
import com.nexora.hotel.dto.request.SetActiveRequest;
import com.nexora.hotel.dto.request.UpdateUserRequest;
import com.nexora.hotel.dto.request.UserOverridesRequest;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;
import com.nexora.hotel.dto.response.UserDetailResponse;
import com.nexora.hotel.dto.response.UserResponse;
import com.nexora.hotel.service.UserAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Administración · Usuarios")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserAdminService service;

    @GetMapping
    @PreAuthorize("hasAuthority('USERS:VIEW')")
    public ApiResponse<PageResponse<UserResponse>> list(@RequestParam(required = false) String q,
                                                        @RequestParam(required = false) Boolean active,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(q, active, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS:VIEW')")
    public ApiResponse<UserDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('USERS:MANAGE')")
    public ApiResponse<TemporaryPasswordResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(service.create(request), "Usuario creado");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS:MANAGE')")
    public ApiResponse<UserResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return ApiResponse.ok(service.update(id, request), "Usuario actualizado");
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasAuthority('USERS:MANAGE')")
    public ApiResponse<UserResponse> setActive(@PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return ApiResponse.ok(service.setActive(id, request.active()),
                request.active() ? "Usuario activado" : "Usuario desactivado");
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('USERS:MANAGE')")
    public ApiResponse<TemporaryPasswordResponse> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(service.resetPassword(id), "Contraseña temporal generada");
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('ROLES:MANAGE')")
    public ApiResponse<UserDetailResponse> setOverrides(@PathVariable Long id,
                                                        @Valid @RequestBody UserOverridesRequest request) {
        return ApiResponse.ok(service.setOverrides(id, request), "Permisos actualizados");
    }
}
