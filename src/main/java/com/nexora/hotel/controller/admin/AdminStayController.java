package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.AddChargeRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckInPreview;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckInRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.CheckOutRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.NoShowRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentLinkRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentLinkResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.RecordPaymentRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.RefundRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.ReverseRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.VoidChargeRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.service.PaymentService;
import com.nexora.hotel.service.StayOperationsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Operación de la estancia y cuenta de la reservación: check-in/out, no-show, cobros, reembolsos y cargos. */
@Tag(name = "Administración · Estancia y cobros")
@RestController
@RequestMapping("/api/admin/reservations/{id}")
@RequiredArgsConstructor
public class AdminStayController {

    private final StayOperationsService stay;
    private final PaymentService payments;

    @GetMapping("/check-in-preview")
    @PreAuthorize("hasAuthority('RESERVATIONS:CHECK_IN')")
    public ApiResponse<CheckInPreview> checkInPreview(@PathVariable Long id) {
        return ApiResponse.ok(stay.checkInPreview(id));
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasAuthority('RESERVATIONS:CHECK_IN')")
    public ApiResponse<ReservationDetail> checkIn(@PathVariable Long id, @Valid @RequestBody CheckInRequest request) {
        return ApiResponse.ok(stay.checkIn(id, request), "Llegada registrada");
    }

    @PostMapping("/check-out")
    @PreAuthorize("hasAuthority('RESERVATIONS:CHECK_OUT')")
    public ApiResponse<ReservationDetail> checkOut(@PathVariable Long id, @Valid @RequestBody CheckOutRequest request) {
        return ApiResponse.ok(stay.checkOut(id, request), "Salida registrada");
    }

    @PostMapping("/no-show")
    @PreAuthorize("hasAuthority('RESERVATIONS:CHECK_IN')")
    public ApiResponse<ReservationDetail> noShow(@PathVariable Long id, @Valid @RequestBody NoShowRequest request) {
        return ApiResponse.ok(stay.noShow(id, request), "Marcada como no presentada");
    }

    @Operation(summary = "Cobro en recepción. Envía Idempotency-Key para que un reintento no cobre dos veces")
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENTS:CREATE')")
    public ApiResponse<ReservationDetail> pay(@PathVariable Long id, @Valid @RequestBody RecordPaymentRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(payments.record(id, request, idempotencyKey), "Pago registrado");
    }

    @PostMapping("/payment-link")
    @PreAuthorize("hasAuthority('PAYMENTS:CREATE_LINK')")
    public ApiResponse<PaymentLinkResponse> link(@PathVariable Long id, @Valid @RequestBody PaymentLinkRequest request) {
        return ApiResponse.ok(payments.createLink(id, request), "Enlace de pago generado");
    }

    @Operation(summary = "Consulta un pago en línea pendiente directamente en la pasarela")
    @PostMapping("/payments/{paymentId}/verify")
    @PreAuthorize("hasAuthority('PAYMENTS:VIEW')")
    public ApiResponse<ReservationDetail> verify(@PathVariable Long id, @PathVariable Long paymentId) {
        return ApiResponse.ok(payments.verify(id, paymentId), "Pago verificado con la pasarela");
    }

    @PostMapping("/payments/{paymentId}/reverse")
    @PreAuthorize("hasAuthority('PAYMENTS:REVERSE')")
    public ApiResponse<ReservationDetail> reverse(@PathVariable Long id, @PathVariable Long paymentId,
                                                  @Valid @RequestBody ReverseRequest request) {
        return ApiResponse.ok(payments.reverse(id, paymentId, request), "Pago reversado");
    }

    @PostMapping("/refunds")
    @PreAuthorize("hasAnyAuthority('PAYMENTS:REFUND', 'PAYMENTS:REFUND_EXTRAORDINARY')")
    public ApiResponse<ReservationDetail> refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(payments.refund(id, request), "Reembolso registrado");
    }

    @PostMapping("/charges")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RESERVATIONS:UPDATE')")
    public ApiResponse<ReservationDetail> addCharge(@PathVariable Long id, @Valid @RequestBody AddChargeRequest request) {
        return ApiResponse.ok(payments.addCharge(id, request), "Cargo agregado");
    }

    @PostMapping("/charges/{chargeId}/void")
    @PreAuthorize("hasAuthority('PAYMENTS:REVERSE')")
    public ApiResponse<ReservationDetail> voidCharge(@PathVariable Long id, @PathVariable Long chargeId,
                                                     @Valid @RequestBody VoidChargeRequest request) {
        return ApiResponse.ok(payments.voidCharge(id, chargeId, request), "Cargo anulado");
    }
}
