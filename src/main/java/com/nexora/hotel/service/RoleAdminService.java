package com.nexora.hotel.service;

import com.nexora.hotel.dto.request.SaveRoleRequest;
import com.nexora.hotel.dto.response.PermissionResponse;
import com.nexora.hotel.dto.response.RoleResponse;

import java.util.List;

/** Roles y permisos del hotel actual (Administración → Seguridad → Roles y permisos). */
public interface RoleAdminService {

    List<PermissionResponse> catalog();

    List<RoleResponse> list();

    RoleResponse create(SaveRoleRequest request);

    RoleResponse update(Long id, SaveRoleRequest request);

    void delete(Long id);
}
