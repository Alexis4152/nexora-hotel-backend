package com.nexora.hotel.controller.publicapi;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.AccountDtos.RegisterRequest;
import com.nexora.hotel.dto.catalog.AccountDtos.ResendRequest;
import com.nexora.hotel.dto.catalog.AccountDtos.VerifyRequest;
import com.nexora.hotel.service.GuestAccountService;
import com.nexora.hotel.tenant.HotelDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Registro de huéspedes en el portal del hotel ({@code X-Hotel}). */
@Tag(name = "Público · Cuenta")
@RestController
@RequestMapping("/api/public/account")
@RequiredArgsConstructor
public class PublicAccountController {

    private static final String SENT = "Si el correo puede registrarse, te enviamos un enlace para confirmar tu cuenta. Revisa tu bandeja de entrada.";

    private final GuestAccountService accounts;
    private final HotelDirectory hotelDirectory;

    @Operation(summary = "Crea la cuenta y envía el enlace de confirmación. Responde igual exista o no el correo")
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        hotelDirectory.current();
        accounts.register(request);
        return ApiResponse.ok(null, SENT);
    }

    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody VerifyRequest request) {
        hotelDirectory.current();
        accounts.verify(request.token());
        return ApiResponse.ok(null, "¡Listo! Tu correo quedó confirmado. Ya puedes iniciar sesión.");
    }

    @PostMapping("/resend")
    public ApiResponse<Void> resend(@Valid @RequestBody ResendRequest request) {
        hotelDirectory.current();
        accounts.resendVerification(request.email());
        return ApiResponse.ok(null, SENT);
    }
}
