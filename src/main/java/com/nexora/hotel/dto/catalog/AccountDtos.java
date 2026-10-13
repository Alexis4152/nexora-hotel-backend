package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.validation.PersonName;
import com.nexora.hotel.validation.StrongPassword;
import com.nexora.hotel.validation.ValidationPatterns;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTOs de la cuenta del huésped en el portal y de las solicitudes de datos personales (ARCO). */
public final class AccountDtos {

    private AccountDtos() {
    }

    // ── Registro ───────────────────────────────────────────────────────────────────────

    public record RegisterRequest(
            @NotBlank(message = "Escribe tu nombre") @Size(max = 100) @PersonName String firstName,
            @NotBlank(message = "Escribe tus apellidos") @Size(max = 100) @PersonName String lastName,
            @NotBlank(message = "Escribe tu correo") @Email(message = "El correo no es válido") @Size(max = 150) String email,
            @Pattern(regexp = ValidationPatterns.COUNTRY_CODE, message = "Código de país inválido") String phoneCountryCode,
            @Pattern(regexp = "^$|^\\d{6,14}$", message = "Teléfono inválido") String phone,
            @NotBlank(message = "Escribe una contraseña") @StrongPassword String password,
            @AssertTrue(message = "Debes aceptar el aviso de privacidad") boolean acceptPrivacy,
            /** Campo trampa para bots. */
            @Size(max = 0, message = "Solicitud no válida") String website) {

        @AssertTrue(message = "El celular de México debe tener exactamente 10 dígitos")
        public boolean isMexicanPhoneValid() {
            return mexicanPhoneOk(phoneCountryCode, phone);
        }
    }

    public record VerifyRequest(@NotBlank @Size(min = 20, max = 100) String token) {
    }

    public record ResendRequest(@NotBlank @Email @Size(max = 150) String email) {
    }

    // ── Perfil ─────────────────────────────────────────────────────────────────────────

    public record ProfileRequest(
            @NotBlank(message = "Escribe tu nombre") @Size(max = 100) @PersonName String firstName,
            @NotBlank(message = "Escribe tus apellidos") @Size(max = 100) @PersonName String lastName,
            @Pattern(regexp = ValidationPatterns.COUNTRY_CODE, message = "Código de país inválido") String phoneCountryCode,
            @Pattern(regexp = "^$|^\\d{6,14}$", message = "Teléfono inválido") String phone,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "País inválido") String country) {

        @AssertTrue(message = "El celular de México debe tener exactamente 10 dígitos")
        public boolean isMexicanPhoneValid() {
            return mexicanPhoneOk(phoneCountryCode, phone);
        }
    }

    public record Profile(String firstName, String lastName, String email, String phoneCountryCode, String phone,
                          String country, Instant memberSince, long stays, List<DataRequestView> requests) {
    }

    // ── Reservaciones y pagos del huésped ──────────────────────────────────────────────

    public record AccountReservation(String folio, ReservationStatus status, LocalDate checkIn, LocalDate checkOut,
                                     int nights, int adults, int children, String roomTypeName, String imageUrl,
                                     BigDecimal total, BigDecimal paid, BigDecimal balance, BigDecimal depositPending,
                                     boolean upcoming) {
    }

    public record AccountPayment(Long id, String folio, PaymentKind kind, PaymentMethod method, PaymentStatus status,
                                 BigDecimal amount, String currency, String cardLast4, Instant createdAt, Instant completedAt) {
    }

    public record AccountPayRequest(@NotNull @Pattern(regexp = "DEPOSIT|BALANCE", message = "Opción de pago inválida") String purpose) {
    }

    // ── Solicitudes de datos (ARCO) ────────────────────────────────────────────────────

    public record DataRequestCreate(
            @NotNull(message = "Elige el tipo de solicitud") @Pattern(regexp = "ACCESS|RECTIFICATION|DELETION|OPPOSITION") String type,
            @Size(max = 2000) String details) {
    }

    public record DataRequestView(Long id, String type, String status, String details, String resolutionNotes,
                                  Instant createdAt, Instant resolvedAt, Long customerId, String customerName,
                                  String customerEmail, String resolvedByName) {
    }

    public record ResolveDataRequest(
            @NotNull @Pattern(regexp = "COMPLETED|REJECTED", message = "Resolución inválida") String status,
            @NotBlank(message = "Explica la resolución") @Size(max = 2000) String notes,
            boolean anonymize) {
    }

    static boolean mexicanPhoneOk(String code, String phone) {
        boolean mexico = code == null || code.isBlank() || "52".equals(code);
        return !mexico || phone == null || phone.isBlank() || phone.matches(ValidationPatterns.MX_PHONE);
    }
}
