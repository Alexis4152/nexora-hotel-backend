package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.IdentityRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.IdentityResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.OperationSettings;
import com.nexora.hotel.dto.catalog.SettingsDtos.PolicyRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.PolicyResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.ThemeRequest;
import com.nexora.hotel.dto.catalog.SettingsDtos.ThemeResponse;
import com.nexora.hotel.service.HotelSettingsService;
import com.nexora.hotel.service.HotelSettingsService.BrandImage;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Administración · Configuración del hotel")
@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
public class AdminSettingsController {

    private final HotelSettingsService service;

    @GetMapping("/operation")
    @PreAuthorize("hasAnyAuthority('SETTINGS:VIEW', 'SETTINGS:UPDATE')")
    public ApiResponse<OperationSettings> operation() {
        return ApiResponse.ok(service.operation());
    }

    @PutMapping("/operation")
    @PreAuthorize("hasAuthority('SETTINGS:UPDATE')")
    public ApiResponse<OperationSettings> updateOperation(@Valid @RequestBody OperationSettings request) {
        return ApiResponse.ok(service.updateOperation(request), "Horarios y reglas guardados");
    }

    @GetMapping("/identity")
    @PreAuthorize("hasAnyAuthority('SETTINGS:VIEW', 'SETTINGS:UPDATE')")
    public ApiResponse<IdentityResponse> identity() {
        return ApiResponse.ok(service.identity());
    }

    @PutMapping("/identity")
    @PreAuthorize("hasAuthority('SETTINGS:UPDATE')")
    public ApiResponse<IdentityResponse> updateIdentity(@Valid @RequestBody IdentityRequest request) {
        return ApiResponse.ok(service.updateIdentity(request), "Datos del hotel guardados");
    }

    @PutMapping("/theme")
    @PreAuthorize("hasAuthority('SETTINGS:UPDATE')")
    public ApiResponse<ThemeResponse> updateTheme(@Valid @RequestBody ThemeRequest request) {
        return ApiResponse.ok(service.updateTheme(request), "Apariencia guardada");
    }

    @PostMapping(value = "/images/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SETTINGS:UPDATE')")
    public ApiResponse<IdentityResponse> uploadImage(@PathVariable BrandImage kind, @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.uploadImage(kind, file), "Imagen actualizada");
    }

    @DeleteMapping("/images/{kind}")
    @PreAuthorize("hasAuthority('SETTINGS:UPDATE')")
    public ApiResponse<IdentityResponse> removeImage(@PathVariable BrandImage kind) {
        return ApiResponse.ok(service.removeImage(kind), "Imagen eliminada");
    }

    @GetMapping("/policies")
    @PreAuthorize("hasAnyAuthority('SETTINGS:VIEW', 'POLICIES:MANAGE')")
    public ApiResponse<PolicyResponse> policies() {
        return ApiResponse.ok(service.policies());
    }

    @PutMapping("/policies")
    @PreAuthorize("hasAuthority('POLICIES:MANAGE')")
    public ApiResponse<PolicyResponse> updatePolicies(@Valid @RequestBody PolicyRequest request) {
        return ApiResponse.ok(service.updatePolicies(request), "Políticas guardadas como nueva versión");
    }
}
