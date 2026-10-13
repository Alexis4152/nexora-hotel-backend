package com.nexora.hotel.controller;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.AccountDtos.AccountPayRequest;
import com.nexora.hotel.dto.catalog.AccountDtos.AccountPayment;
import com.nexora.hotel.dto.catalog.AccountDtos.AccountReservation;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestCreate;
import com.nexora.hotel.dto.catalog.AccountDtos.DataRequestView;
import com.nexora.hotel.dto.catalog.AccountDtos.Profile;
import com.nexora.hotel.dto.catalog.AccountDtos.ProfileRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentStart;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicCancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationView;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.service.GuestAccountService;
import com.nexora.hotel.service.HotelSettingsService;
import com.nexora.hotel.service.ReceiptPdfService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** "Mi cuenta" del huésped registrado: perfil, reservaciones, pagos, comprobantes y sus datos personales. */
@Tag(name = "Cuenta del huésped")
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final GuestAccountService accounts;
    private final ReceiptPdfService receipts;
    private final HotelSettingsService settings;
    private final HotelDirectory hotelDirectory;
    private final ObjectMapper objectMapper;

    @GetMapping("/profile")
    public ApiResponse<Profile> profile() {
        return ApiResponse.ok(accounts.profile());
    }

    @PutMapping("/profile")
    public ApiResponse<Profile> updateProfile(@Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(accounts.updateProfile(request), "Datos actualizados");
    }

    @GetMapping("/reservations")
    public ApiResponse<List<AccountReservation>> reservations() {
        return ApiResponse.ok(accounts.reservations());
    }

    @GetMapping("/reservations/{folio}")
    public ApiResponse<PublicReservationView> reservation(@PathVariable String folio) {
        return ApiResponse.ok(accounts.reservation(folio));
    }

    @PostMapping("/reservations/{folio}/cancel")
    public ApiResponse<PublicReservationView> cancel(@PathVariable String folio, @Valid @RequestBody PublicCancelRequest request) {
        return ApiResponse.ok(accounts.cancel(folio, request), "Reservación cancelada");
    }

    @PostMapping("/reservations/{folio}/payments")
    public ApiResponse<PublicPaymentStart> pay(@PathVariable String folio, @Valid @RequestBody AccountPayRequest request) {
        return ApiResponse.ok(accounts.pay(folio, request.purpose()));
    }

    @GetMapping("/reservations/{folio}/receipt.pdf")
    public ResponseEntity<byte[]> receipt(@PathVariable String folio) {
        ReservationDetail detail = accounts.receiptDetail(folio);
        byte[] pdf = receipts.render(detail, settings.publicView(), hotelDirectory.current().getCurrency());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + detail.summary().folio() + ".pdf\"")
                .body(pdf);
    }

    @GetMapping("/payments")
    public ApiResponse<List<AccountPayment>> payments() {
        return ApiResponse.ok(accounts.payments());
    }

    @GetMapping("/data-export")
    public ResponseEntity<byte[]> export() throws Exception {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(accounts.export());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"mis-datos.json\"").body(json);
    }

    @PostMapping("/data-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DataRequestView> dataRequest(@Valid @RequestBody DataRequestCreate request) {
        return ApiResponse.ok(accounts.requestData(request), "Recibimos tu solicitud. El hotel te responderá por correo.");
    }
}
