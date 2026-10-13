package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.request.SaveRoleRequest;
import com.nexora.hotel.dto.response.PermissionResponse;
import com.nexora.hotel.dto.response.RoleResponse;
import com.nexora.hotel.service.RoleAdminService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Administración · Roles y permisos")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleAdminService service;

    @GetMapping("/permissions")
    @PreAuthorize("hasAnyAuthority('ROLES:MANAGE', 'USERS:VIEW')")
    public ApiResponse<List<PermissionResponse>> catalog() {
        return ApiResponse.ok(service.catalog());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyAuthority('ROLES:MANAGE', 'USERS:VIEW')")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLES:MANAGE')")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody SaveRoleRequest request) {
        return ApiResponse.ok(service.create(request), "Rol creado");
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLES:MANAGE')")
    public ApiResponse<RoleResponse> update(@PathVariable Long id, @Valid @RequestBody SaveRoleRequest request) {
        return ApiResponse.ok(service.update(id, request), "Permisos guardados");
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('ROLES:MANAGE')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null, "Rol eliminado");
    }
}
