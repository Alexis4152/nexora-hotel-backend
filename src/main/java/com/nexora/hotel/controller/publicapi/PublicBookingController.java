package com.nexora.hotel.controller.publicapi;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.AvailabilityResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.LookupRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicCancelRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationCreated;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.PublicReservationView;
import com.nexora.hotel.service.AvailabilityService;
import com.nexora.hotel.service.ReservationService;
import com.nexora.hotel.tenant.HotelDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Reservación en línea sin cuenta. El acceso a una reservación exige folio + token de 256 bits (enviado en el
 * header {@code X-Reservation-Token}, nunca en la URL de la API para que no quede en logs). El folio solo no basta.
 */
@Tag(name = "Público · Reservaciones")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicBookingController {

    public static final String TOKEN_HEADER = "X-Reservation-Token";

    private final AvailabilityService availability;
    private final ReservationService reservations;
    private final HotelDirectory hotelDirectory;

    @Operation(summary = "Tipos de habitación disponibles y precio total para las fechas y huéspedes")
    @GetMapping("/availability")
    public ApiResponse<AvailabilityResponse> availability(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
                                                          @RequestParam(defaultValue = "2") int adults,
                                                          @RequestParam(defaultValue = "0") int children) {
        hotelDirectory.current();
        if (adults > 20 || children > 20) {
            throw new com.nexora.hotel.exception.BusinessException("La cantidad de huéspedes no es válida");
        }
        return ApiResponse.ok(availability.search(checkIn, checkOut, adults, children, true));
    }

    @Operation(summary = "Crea una solicitud de reservación (queda pendiente de confirmación del hotel)")
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PublicReservationCreated> create(@Valid @RequestBody PublicReservationRequest request) {
        hotelDirectory.current();
        return ApiResponse.ok(reservations.createPublic(request), "Recibimos tu solicitud");
    }

    @GetMapping("/reservations/{folio}")
    public ApiResponse<PublicReservationView> view(@PathVariable String folio,
                                                   @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        return ApiResponse.ok(reservations.publicView(folio, token));
    }

    @PostMapping("/reservations/{folio}/cancel")
    public ApiResponse<PublicReservationView> cancel(@PathVariable String folio,
                                                     @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                                     @Valid @RequestBody PublicCancelRequest request) {
        return ApiResponse.ok(reservations.cancelByGuest(folio, token, request), "Reservación cancelada");
    }

    @Operation(summary = "Envía al correo de la reservación un enlace de acceso. Responde igual exista o no")
    @PostMapping("/reservations/lookup")
    public ApiResponse<Void> lookup(@Valid @RequestBody LookupRequest request) {
        hotelDirectory.current();
        reservations.sendAccessLink(request);
        return ApiResponse.ok(null, "Si los datos coinciden con una reservación, te enviamos un enlace de acceso a tu correo.");
    }
}
