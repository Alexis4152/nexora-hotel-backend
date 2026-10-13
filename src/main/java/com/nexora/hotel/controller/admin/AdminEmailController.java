package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.EmailSettingsRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.EmailSettingsResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.OutboxEntry;
import com.nexora.hotel.dto.catalog.ReservationDtos.TemplateRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.TemplateResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.TestEmailRequest;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.notification.MailService;
import com.nexora.hotel.service.EmailSettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Administración · Correo y notificaciones")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEmailController {

    private final EmailSettingsService service;

    @GetMapping("/email-settings")
    @PreAuthorize("hasAuthority('EMAIL_SETTINGS:MANAGE')")
    public ApiResponse<EmailSettingsResponse> settings() {
        return ApiResponse.ok(service.get());
    }

    @PutMapping("/email-settings")
    @PreAuthorize("hasAuthority('EMAIL_SETTINGS:MANAGE')")
    public ApiResponse<EmailSettingsResponse> update(@Valid @RequestBody EmailSettingsRequest request) {
        return ApiResponse.ok(service.update(request), "Configuración de correo guardada");
    }

    @PostMapping("/email-settings/test")
    @PreAuthorize("hasAuthority('EMAIL_SETTINGS:MANAGE')")
    public ApiResponse<MailService.Delivery> test(@Valid @RequestBody TestEmailRequest request) {
        MailService.Delivery d = service.sendTest(request.to());
        return d.outcome() == MailService.Outcome.SENT
                ? ApiResponse.ok(d, "Correo de prueba enviado")
                : ApiResponse.<MailService.Delivery>builder().success(false).data(d)
                .message("No se pudo enviar: " + d.error()).build();
    }

    @GetMapping("/notification-templates")
    @PreAuthorize("hasAuthority('NOTIFICATIONS:MANAGE')")
    public ApiResponse<List<TemplateResponse>> templates() {
        return ApiResponse.ok(service.templates());
    }

    @PutMapping("/notification-templates/{event}")
    @PreAuthorize("hasAuthority('NOTIFICATIONS:MANAGE')")
    public ApiResponse<TemplateResponse> updateTemplate(@PathVariable NotificationEvent event, @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.ok(service.updateTemplate(event, request), "Plantilla guardada");
    }

    @DeleteMapping("/notification-templates/{event}")
    @PreAuthorize("hasAuthority('NOTIFICATIONS:MANAGE')")
    public ApiResponse<Void> resetTemplate(@PathVariable NotificationEvent event) {
        service.resetTemplate(event);
        return ApiResponse.ok(null, "Plantilla restaurada");
    }

    @GetMapping("/notifications/outbox")
    @PreAuthorize("hasAnyAuthority('NOTIFICATIONS:MANAGE', 'EMAIL_SETTINGS:MANAGE')")
    public ApiResponse<List<OutboxEntry>> outbox() {
        return ApiResponse.ok(service.recent());
    }
}
