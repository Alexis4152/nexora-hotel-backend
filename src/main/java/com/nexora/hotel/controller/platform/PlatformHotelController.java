package com.nexora.hotel.controller.platform;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.request.CreateHotelRequest;
import com.nexora.hotel.dto.request.UpdateHotelRequest;
import com.nexora.hotel.dto.response.HotelSummary;
import com.nexora.hotel.dto.response.ProvisionedHotelResponse;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;
import com.nexora.hotel.service.HotelProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Panel de Nexora: alta de hoteles y entrega de su enlace y administrador al dueño. */
@Tag(name = "Plataforma")
@RestController
@RequestMapping("/api/platform/hotels")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlatformHotelController {

    private final HotelProvisioningService service;

    @GetMapping
    public ApiResponse<List<HotelSummary>> list() {
        return ApiResponse.ok(service.list());
    }

    @Operation(summary = "Crea un hotel con su primer administrador (devuelve la contraseña temporal una sola vez)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProvisionedHotelResponse> create(@Valid @RequestBody CreateHotelRequest request) {
        return ApiResponse.ok(service.create(request), "Hotel creado");
    }

    @PutMapping("/{id}")
    public ApiResponse<HotelSummary> update(@PathVariable Long id, @Valid @RequestBody UpdateHotelRequest request) {
        return ApiResponse.ok(service.update(id, request), "Hotel actualizado");
    }

    @PostMapping("/{id}/admin-password")
    public ApiResponse<TemporaryPasswordResponse> resetAdminPassword(@PathVariable Long id) {
        return ApiResponse.ok(service.resetAdminPassword(id), "Contraseña temporal generada");
    }
}
