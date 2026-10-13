package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.domain.CancellationEvaluator.Outcome;
import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
import com.nexora.hotel.enums.ReservationEnums.ReservationSource;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.repository.ReservationLedger;
import com.nexora.hotel.validation.PersonName;
import com.nexora.hotel.validation.ValidationPatterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** DTOs de disponibilidad, reservaciones, clientes y correo. */
public final class ReservationDtos {

    private ReservationDtos() {
    }

    // ── Entradas comunes ───────────────────────────────────────────────────────────────

    /**
     * Datos del huésped. El teléfono se valida como 10 dígitos cuando el país es México (código 52) y como
     * internacional en otro caso.
     */
    public record GuestInput(
            @NotBlank(message = "Escribe el nombre") @Size(max = 100) @PersonName String firstName,
            @NotBlank(message = "Escribe los apellidos") @Size(max = 100) @PersonName String lastName,
            @Email(message = "El correo no es válido") @Size(max = 150) String email,
            @Pattern(regexp = ValidationPatterns.COUNTRY_CODE, message = "Código de país inválido") String phoneCountryCode,
            @Pattern(regexp = "^$|^\\d{6,14}$", message = "Teléfono inválido") String phone,
            @Pattern(regexp = "^$|^[A-Z]{2}$", message = "País inválido") String country) {

        @AssertTrue(message = "El celular de México debe tener exactamente 10 dígitos")
        public boolean isMexicanPhoneValid() {
            boolean mexico = phoneCountryCode == null || phoneCountryCode.isBlank() || "52".equals(phoneCountryCode);
            return !mexico || phone == null || phone.isBlank() || phone.matches(ValidationPatterns.MX_PHONE);
        }
    }

    public record ServiceSelection(@NotNull Long serviceId, @Min(1) @Max(20) int units) {
    }

    public interface StayDates {
        LocalDate checkIn();

        LocalDate checkOut();

        @AssertTrue(message = "La fecha de salida debe ser posterior a la de entrada")
        default boolean isDatesValid() {
            return checkIn() == null || checkOut() == null || checkOut().isAfter(checkIn());
        }
    }

    // ── Disponibilidad ─────────────────────────────────────────────────────────────────

    public record AvailabilityOption(Long roomTypeId, String name, String description, int maxAdults, int maxChildren,
                                     int maxOccupancy, String bedType, int bedCount, BigDecimal sizeM2, String imageUrl,
                                     List<String> amenities, int availableRooms, boolean fitsGuests,
                                     BigDecimal lodgingTotal, BigDecimal taxes, BigDecimal total, BigDecimal averageNight,
                                     List<NightPrice> nights) {
    }

    public record NightPrice(LocalDate date, BigDecimal price, String ruleName) {
    }

    public record AvailabilityResponse(LocalDate checkIn, LocalDate checkOut, int nights, int adults, int children,
                                       List<AvailabilityOption> options) {
    }

    // ── Reservaciones (personal) ───────────────────────────────────────────────────────

    public record StaffReservationRequest(
            Long customerId,
            @Valid GuestInput guest,
            @NotNull(message = "Elige el tipo de habitación") Long roomTypeId,
            Long roomId,
            @NotNull(message = "Indica la entrada") LocalDate checkIn,
            @NotNull(message = "Indica la salida") LocalDate checkOut,
            @Min(1) @Max(20) int adults,
            @Min(0) @Max(20) int children,
            @Valid List<ServiceSelection> services,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discount,
            @NotNull ReservationSource source,
            @Size(max = 1000) String specialRequests,
            @Size(max = 2000) String internalNotes,
            boolean confirmNow) implements StayDates {

        @AssertTrue(message = "Elige un cliente o captura los datos del huésped")
        public boolean isGuestPresent() {
            return customerId != null || guest != null;
        }
    }

    public record ModifyRequest(
            @NotNull Long roomTypeId,
            Long roomId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @Min(1) @Max(20) int adults,
            @Min(0) @Max(20) int children,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal discount,
            @NotBlank(message = "Indica el motivo del cambio") @Size(max = 500) String reason) implements StayDates {
    }

    public record CancelRequest(@NotBlank(message = "Indica el motivo") @Size(max = 500) String reason, boolean waivePenalty) {
    }

    public record NotesRequest(@Size(max = 1000) String specialRequests, @Size(max = 2000) String internalNotes) {
    }

    public record CancellationPreview(Outcome outcome, BigDecimal penalty, ZonedDateTime freeUntil, ZonedDateTime checkInAt) {
    }

    public record ReservationSummary(Long id, String folio, ReservationStatus status, ReservationSource source,
                                     String guestName, String contactPhone, LocalDate checkIn, LocalDate checkOut,
                                     int nights, int adults, int children, String roomNumber, String roomTypeName,
                                     BigDecimal total, Instant createdAt, BigDecimal paid, BigDecimal balance) {
    }

    public record ServiceLine(Long serviceId, String name, ChargeMode chargeMode, int units, int quantity,
                              BigDecimal unitPrice, BigDecimal total) {
    }

    public record ReservationDetail(ReservationSummary summary, Long customerId, String contactEmail,
                                    String specialRequests, String internalNotes, BigDecimal roomSubtotal,
                                    BigDecimal extraGuestsTotal, BigDecimal servicesTotal, BigDecimal discountTotal,
                                    BigDecimal taxTotal, BigDecimal penaltyAmount, int priceRevision,
                                    List<ReservationLedger.PriceLine> priceLines, List<ServiceLine> services,
                                    List<ReservationLedger.StatusChange> history, Map<String, Object> policy,
                                    CancellationPreview cancellation, Long roomId, Long roomTypeId, Instant expiresAt,
                                    Instant confirmedAt, Instant cancelledAt, String cancelReason, long version,
                                    PaymentDtos.Account account) {
    }

    public record CalendarRoom(Long id, String number, String floor, String roomTypeName, String status) {
    }

    public record CalendarEntry(Long roomId, String kind, LocalDate starts, LocalDate ends, Long reservationId,
                                String folio, String guestName, String status, String reason) {
    }

    public record CalendarResponse(LocalDate from, LocalDate to, List<CalendarRoom> rooms, List<CalendarEntry> entries) {
    }

    public record DayBoard(List<ReservationSummary> arrivals, List<ReservationSummary> departures,
                           List<ReservationSummary> inHouse, List<ReservationSummary> pending) {
    }

    // ── Portal público ─────────────────────────────────────────────────────────────────

    public record PublicReservationRequest(
            @NotNull Long roomTypeId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @Min(1) @Max(20) int adults,
            @Min(0) @Max(20) int children,
            @NotNull @Valid GuestInput guest,
            @Valid List<ServiceSelection> services,
            @Size(max = 1000) String specialRequests,
            @AssertTrue(message = "Debes aceptar las políticas del hotel") boolean acceptTerms,
            @AssertTrue(message = "Debes aceptar el aviso de privacidad") boolean acceptPrivacy,
            /** Campo trampa: invisible para personas; si llega con valor, es un bot. */
            @Size(max = 0, message = "Solicitud no válida") String website,
            /** null o true: pagar el anticipo en línea si el hotel lo ofrece; false: pagar en el hotel. */
            Boolean payOnline) implements StayDates {

        @AssertTrue(message = "El correo es obligatorio para enviarte la confirmación")
        public boolean isEmailPresent() {
            return guest == null || (guest.email() != null && !guest.email().isBlank());
        }
    }

    public record PublicReservationCreated(String folio, String accessToken, ReservationStatus status,
                                           boolean paymentRequired, BigDecimal amountDue) {
    }

    public record PublicReservationView(String folio, ReservationStatus status, String hotelName, String guestName,
                                        LocalDate checkIn, LocalDate checkOut, int nights, int adults, int children,
                                        String roomTypeName, List<ServiceLine> services,
                                        List<ReservationLedger.PriceLine> priceLines, BigDecimal total,
                                        String currency, Map<String, Object> policy, CancellationPreview cancellation,
                                        boolean canCancel, Instant expiresAt, BigDecimal paid, BigDecimal balance,
                                        BigDecimal depositPending, PaymentDtos.PublicPaymentOptions payment) {
    }

    public record PublicCancelRequest(@Size(max = 500) String reason, boolean acceptPenalty) {
    }

    public record LookupRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{6,12}$", message = "Folio inválido") String folio,
            @NotBlank @Email @Size(max = 150) String email) {
    }

    // ── Clientes ───────────────────────────────────────────────────────────────────────

    public record CustomerRequest(@NotNull @Valid GuestInput guest, @Size(max = 1000) String preferences,
                                  @Size(max = 2000) String notes) {
    }

    public record CustomerResponse(Long id, String firstName, String lastName, String email, String phoneCountryCode,
                                   String phone, String country, String preferences, String notes, String status,
                                   boolean hasAccount, long reservations, LocalDate lastStay, Instant createdAt) {
    }

    public record CustomerDetail(CustomerResponse customer, List<ReservationSummary> reservations) {
    }

    // ── Correo ─────────────────────────────────────────────────────────────────────────

    public record EmailSettingsRequest(
            boolean enabled,
            @Size(max = 200) @Pattern(regexp = "^$|^[A-Za-z0-9.-]{3,200}$", message = "Servidor inválido") String host,
            @Min(1) @Max(65535) int port,
            @Size(max = 200) String username,
            /** null = conservar la contraseña guardada. */
            @Size(max = 300) String password,
            boolean starttls,
            @Email @Size(max = 150) String fromAddress,
            @Size(max = 150) String fromName,
            @Email @Size(max = 150) String replyTo,
            @Size(max = 1000) String adminRecipients,
            @Size(max = 1000) String bccRecipients) {
    }

    public record EmailSettingsResponse(boolean enabled, String host, int port, String username, boolean passwordSet,
                                        boolean starttls, String fromAddress, String fromName, String replyTo,
                                        String adminRecipients, String bccRecipients, boolean usable) {
    }

    public record TestEmailRequest(@NotBlank @Email String to) {
    }

    public record TemplateRequest(
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            boolean enabled,
            boolean sendToGuest,
            boolean sendToAdmins) {
    }

    public record TemplateResponse(String eventCode, String label, String subject, String body, boolean enabled,
                                   boolean sendToGuest, boolean sendToAdmins, List<String> variables) {
    }

    public record OutboxEntry(Long id, String eventCode, String recipient, String subject, String status, int attempts,
                              String lastError, Instant createdAt, Instant sentAt) {
    }
}
