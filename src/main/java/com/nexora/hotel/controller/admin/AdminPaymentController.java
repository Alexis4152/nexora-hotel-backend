package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashAdjustRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashSessionRow;
import com.nexora.hotel.dto.catalog.PaymentDtos.CashSessionView;
import com.nexora.hotel.dto.catalog.PaymentDtos.CloseCashRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.OpenCashRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentSettingsRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentSettingsResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentsReport;
import com.nexora.hotel.dto.catalog.PaymentDtos.ReconciliationItem;
import com.nexora.hotel.dto.catalog.PaymentDtos.WebhookCredentials;
import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.service.CashService;
import com.nexora.hotel.service.PaymentQueryService;
import com.nexora.hotel.service.PaymentSettingsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Consulta de pagos, conciliación, caja de recepción y configuración de cobro. */
@Tag(name = "Administración · Pagos y caja")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentQueryService queries;
    private final CashService cash;
    private final PaymentSettingsService settings;

    // ── Pagos ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/payments")
    @PreAuthorize("hasAuthority('PAYMENTS:VIEW')")
    public ApiResponse<PaymentsReport> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) PaymentMethod method,
                                            @RequestParam(required = false) PaymentStatus status,
                                            @RequestParam(required = false) PaymentKind kind,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.ok(queries.list(from, to, method, status, kind, q, page, size));
    }

    @GetMapping("/payments/reconciliation")
    @PreAuthorize("hasAuthority('PAYMENTS:VIEW')")
    public ApiResponse<List<ReconciliationItem>> reconciliation() {
        return ApiResponse.ok(queries.reconciliation());
    }

    // ── Caja ───────────────────────────────────────────────────────────────────────────

    @GetMapping("/cash/current")
    @PreAuthorize("hasAuthority('CASH:OPERATE')")
    public ApiResponse<CashSessionView> current() {
        return ApiResponse.ok(cash.current().orElse(null));
    }

    @PostMapping("/cash/open")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CASH:OPERATE')")
    public ApiResponse<CashSessionView> open(@Valid @RequestBody OpenCashRequest request) {
        return ApiResponse.ok(cash.open(request), "Caja abierta");
    }

    @PostMapping("/cash/{id}/adjust")
    @PreAuthorize("hasAuthority('CASH:ADJUST')")
    public ApiResponse<CashSessionView> adjust(@PathVariable Long id, @Valid @RequestBody CashAdjustRequest request) {
        return ApiResponse.ok(cash.adjust(id, request), "Movimiento registrado");
    }

    @PostMapping("/cash/{id}/close")
    @PreAuthorize("hasAnyAuthority('CASH:OPERATE', 'CASH:VIEW_ALL')")
    public ApiResponse<CashSessionView> close(@PathVariable Long id, @Valid @RequestBody CloseCashRequest request) {
        return ApiResponse.ok(cash.close(id, request), "Caja cerrada");
    }

    @GetMapping("/cash/sessions")
    @PreAuthorize("hasAnyAuthority('CASH:OPERATE', 'CASH:VIEW_ALL')")
    public ApiResponse<PageResponse<CashSessionRow>> sessions(@RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "15") int size,
                                                              @RequestParam(required = false) String q) {
        return ApiResponse.ok(cash.list(page, size, q));
    }

    @GetMapping("/cash/sessions/{id}")
    @PreAuthorize("hasAnyAuthority('CASH:OPERATE', 'CASH:VIEW_ALL')")
    public ApiResponse<CashSessionView> session(@PathVariable Long id) {
        return ApiResponse.ok(cash.detail(id));
    }

    // ── Configuración de cobro ─────────────────────────────────────────────────────────

    @GetMapping("/payment-settings")
    @PreAuthorize("hasAuthority('PAYMENT_SETTINGS:MANAGE')")
    public ApiResponse<PaymentSettingsResponse> getSettings() {
        return ApiResponse.ok(settings.get());
    }

    @PutMapping("/payment-settings")
    @PreAuthorize("hasAuthority('PAYMENT_SETTINGS:MANAGE')")
    public ApiResponse<PaymentSettingsResponse> updateSettings(@Valid @RequestBody PaymentSettingsRequest request) {
        return ApiResponse.ok(settings.update(request), "Configuración de cobro guardada");
    }

    @PostMapping("/payment-settings/test")
    @PreAuthorize("hasAuthority('PAYMENT_SETTINGS:MANAGE')")
    public ApiResponse<Void> test() {
        return ApiResponse.ok(null, settings.test());
    }

    @PostMapping("/payment-settings/webhook")
    @PreAuthorize("hasAuthority('PAYMENT_SETTINGS:MANAGE')")
    public ApiResponse<WebhookCredentials> regenerateWebhook() {
        return ApiResponse.ok(settings.regenerateWebhook(), "Credenciales del webhook generadas: cópialas en Openpay");
    }
}
