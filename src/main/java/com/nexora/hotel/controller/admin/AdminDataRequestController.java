package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestView;
import com.nexora.hotel.dto.catalog.AccountDtos.ResolveDataRequest;
import com.nexora.hotel.service.DataRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Solicitudes de datos personales (ARCO) de los huéspedes. */
@Tag(name = "Administración · Datos personales")
@RestController
@RequestMapping("/api/admin/data-requests")
@RequiredArgsConstructor
public class AdminDataRequestController {

    private final DataRequestService service;

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMERS:VIEW')")
    public ApiResponse<List<DataRequestView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(status));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('CUSTOMERS:UPDATE')")
    public ApiResponse<DataRequestView> resolve(@PathVariable Long id, @Valid @RequestBody ResolveDataRequest request) {
        return ApiResponse.ok(service.resolve(id, request), "Solicitud resuelta");
    }
}
