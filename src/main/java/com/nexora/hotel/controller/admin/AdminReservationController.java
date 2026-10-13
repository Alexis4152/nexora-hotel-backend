package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.AvailabilityResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CalendarResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.CancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.DayBoard;
import com.nexora.hotel.dto.catalog.ReservationDtos.ModifyRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.NotesRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationDetail;
import com.nexora.hotel.dto.catalog.ReservationDtos.ReservationSummary;
import com.nexora.hotel.dto.catalog.ReservationDtos.StaffReservationRequest;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.service.AvailabilityService;
import com.nexora.hotel.service.HotelSettingsService;
import com.nexora.hotel.service.ReceiptPdfService;
import com.nexora.hotel.service.ReservationQueryService;
import com.nexora.hotel.service.ReservationService;
import com.nexora.hotel.tenant.HotelDirectory;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@Tag(name = "Administración · Reservaciones")
@RestController
@RequestMapping("/api/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private final ReservationService service;
    private final ReservationQueryService queries;
    private final AvailabilityService availability;
    private final ReceiptPdfService receipts;
    private final HotelSettingsService settingsService;
    private final HotelDirectory hotelDirectory;

    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('AVAILABILITY:VIEW')")
    public ApiResponse<AvailabilityResponse> availability(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                                                          @RequestParam(defaultValue = "2") int adults,
                                                          @RequestParam(defaultValue = "0") int children) {
        return ApiResponse.ok(availability.search(checkIn, checkOut, adults, children, false));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RESERVATIONS:VIEW')")
    public ApiResponse<PageResponse<ReservationSummary>> list(@RequestParam(required = false) String q,
                                                              @RequestParam(required = false) List<ReservationStatus> status,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queries.list(q, status, from, to, page, size));
    }

    @GetMapping("/board")
    @PreAuthorize("hasAuthority('RESERVATIONS:VIEW')")
    public ApiResponse<DayBoard> board() {
        return ApiResponse.ok(queries.board());
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAnyAuthority('RESERVATIONS:VIEW', 'ROOMS:VIEW')")
    public ApiResponse<CalendarResponse> calendar(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(queries.calendar(from, to));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('RESERVATIONS:VIEW')")
    public ApiResponse<ReservationDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(queries.detail(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RESERVATIONS:CREATE')")
    public ApiResponse<ReservationDetail> create(@Valid @RequestBody StaffReservationRequest request) {
        return ApiResponse.ok(service.createByStaff(request), "Reservación creada");
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('RESERVATIONS:CONFIRM')")
    public ApiResponse<ReservationDetail> confirm(@PathVariable Long id) {
        return ApiResponse.ok(service.confirm(id), "Reservación confirmada");
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('RESERVATIONS:CANCEL')")
    public ApiResponse<ReservationDetail> cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(service.cancelByStaff(id, request), "Reservación cancelada");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('RESERVATIONS:UPDATE')")
    public ApiResponse<ReservationDetail> modify(@PathVariable Long id, @Valid @RequestBody ModifyRequest request) {
        return ApiResponse.ok(service.modify(id, request), "Reservación actualizada");
    }

    @PutMapping("/{id}/notes")
    @PreAuthorize("hasAuthority('RESERVATIONS:UPDATE')")
    public ApiResponse<ReservationDetail> notes(@PathVariable Long id, @Valid @RequestBody NotesRequest request) {
        return ApiResponse.ok(service.updateNotes(id, request), "Notas guardadas");
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('RESERVATIONS:RESEND')")
    public ApiResponse<Void> resend(@PathVariable Long id) {
        service.resendConfirmation(id);
        return ApiResponse.ok(null, "Correo enviado a la cola de envío");
    }

    @GetMapping("/{id}/receipt.pdf")
    @PreAuthorize("hasAuthority('RESERVATIONS:VIEW')")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id) {
        ReservationDetail detail = queries.detail(id);
        byte[] pdf = receipts.render(detail, settingsService.publicView(), hotelDirectory.current().getCurrency());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("reservacion-" + detail.summary().folio() + ".pdf").build().toString())
                .body(pdf);
    }
}
