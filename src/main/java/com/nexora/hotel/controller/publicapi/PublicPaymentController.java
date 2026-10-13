package com.nexora.hotel.controller.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPayRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentOptions;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStart;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStatus;
import com.nexora.hotel.service.PaymentService;
import com.nexora.hotel.service.PaymentSettingsService;
import com.nexora.hotel.service.PaymentWebhookService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Pago en línea del huésped y webhooks de la pasarela. */
@Tag(name = "Público · Pagos")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicPaymentController {

    private final PaymentService payments;
    private final PaymentSettingsService settings;
    private final PaymentWebhookService webhooks;
    private final HotelDirectory hotelDirectory;

    @Operation(summary = "Métodos de pago que acepta el hotel")
    @GetMapping("/payment-options")
    public ApiResponse<PublicPaymentOptions> options() {
        hotelDirectory.current();
        return ApiResponse.ok(settings.publicOptions());
    }

    @Operation(summary = "Inicia el pago en línea del anticipo o del saldo; devuelve la URL de la página de pago")
    @PostMapping("/reservations/{folio}/payments")
    public ApiResponse<PublicPaymentStart> pay(@PathVariable String folio,
                                               @RequestHeader(value = PublicBookingController.TOKEN_HEADER, required = false) String token,
                                               @Valid @RequestBody PublicPayRequest request) {
        hotelDirectory.current();
        return ApiResponse.ok(payments.startGuest(folio, token, request.purpose()));
    }

    @Operation(summary = "Estado de un pago (página de regreso); se verifica con la pasarela, nunca con el navegador")
    @GetMapping("/payments/{ref}")
    public ApiResponse<PublicPaymentStatus> status(@PathVariable String ref) {
        hotelDirectory.current();
        return ApiResponse.ok(payments.publicStatus(ref));
    }

    /**
     * Webhook de Openpay. El hotel viene en la ruta (Openpay no manda encabezados propios). Respuestas: 200 si se
     * procesó o se ignoró, 401 con credenciales inválidas, 503 si no se pudo verificar con la pasarela (Openpay
     * reintenta).
     */
    @Operation(summary = "Webhook de Openpay (autenticación HTTP Basic configurada por el hotel)")
    @PostMapping("/webhooks/openpay/{slug}")
    public ResponseEntity<Void> openpay(@PathVariable String slug,
                                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @RequestBody JsonNode event) {
        Long hotelId = slug.matches("[a-z0-9-]{3,60}") ? hotelDirectory.activeIdBySlug(slug) : null;
        if (hotelId == null) {
            return ResponseEntity.notFound().build();
        }
        PaymentWebhookService.Result result = TenantContext.callAs(hotelId, () -> webhooks.handleOpenpay(authorization, event));
        return switch (result) {
            case UNAUTHORIZED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case RETRY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            default -> ResponseEntity.ok().build();
        };
    }
}
