package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.enums.PaymentEnums.CashMovementType;
import com.nexora.hotel.enums.PaymentEnums.CashSessionStatus;
import com.nexora.hotel.enums.PaymentEnums.Direction;
import com.nexora.hotel.enums.PaymentEnums.IdType;
import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** DTOs de cuenta de la reservación, pagos, caja, check-in / check-out y configuración de cobro. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    // ── Cuenta de la reservación ───────────────────────────────────────────────────────

    public record PaymentView(Long id, PaymentKind kind, PaymentMethod method, PaymentStatus status, BigDecimal amount,
                              String reference, String notes, Long relatedPaymentId, String provider, String cardBrand,
                              String cardLast4, String checkoutUrl, Instant expiresAt, String failureReason,
                              String createdByName, Instant createdAt, Instant completedAt,
                              /** Lo que aún se puede reembolsar o reversar de este pago. */ BigDecimal remaining) {
    }

    public record ChargeView(Long id, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                             String createdByName, Instant createdAt, boolean voided, String voidReason, Instant voidedAt) {
    }

    public record GuestView(Long id, String fullName, boolean minor, IdType idType, String idNumber) {
    }

    public record Account(BigDecimal stayTotal, BigDecimal penalty, BigDecimal chargesTotal, BigDecimal amountDue,
                          BigDecimal paid, BigDecimal balance, BigDecimal depositRequired, BigDecimal depositPending,
                          List<PaymentView> payments, List<ChargeView> charges, List<GuestView> guests,
                          Instant checkedInAt, String checkedInByName, String checkInNotes,
                          Instant checkedOutAt, String checkedOutByName, String checkOutNotes, Instant noShowAt,
                          BigDecimal noShowPenalty, boolean onlineAvailable) {
    }

    // ── Cobros ─────────────────────────────────────────────────────────────────────────

    public record RecordPaymentRequest(
            @NotNull(message = "Elige el método de pago") PaymentMethod method,
            @NotNull(message = "Indica el importe") @DecimalMin(value = "0.01", message = "El importe debe ser mayor a cero")
            @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 100) String reference,
            @Pattern(regexp = "^$|^\\d{4}$", message = "Escribe solo los últimos 4 dígitos") String cardLast4,
            @Size(max = 500) String notes) {

        @AssertTrue(message = "Los cobros en línea se hacen con un enlace de pago")
        public boolean isPresencial() {
            return method == null || method.isPresencial();
        }
    }

    public record PaymentLinkRequest(
            @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            boolean sendEmail) {
    }

    public record PaymentLinkResponse(Long paymentId, String url, BigDecimal amount, Instant expiresAt, boolean emailed) {
    }

    public record RefundRequest(
            @NotNull(message = "Elige cómo se devuelve") PaymentMethod method,
            @NotNull @DecimalMin(value = "0.01", message = "El importe debe ser mayor a cero") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            Long relatedPaymentId,
            @Size(max = 100) String reference,
            @NotBlank(message = "Indica el motivo") @Size(max = 500) String reason) {
    }

    public record ReverseRequest(@NotBlank(message = "Indica el motivo del reverso") @Size(max = 500) String reason) {
    }

    public record AddChargeRequest(
            Long serviceId,
            @NotBlank(message = "Describe el cargo") @Size(max = 150) String description,
            @NotNull @DecimalMin(value = "0.01") @DecimalMax("999") @Digits(integer = 3, fraction = 2) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice) {
    }

    public record VoidChargeRequest(@NotBlank(message = "Indica el motivo") @Size(max = 300) String reason) {
    }

    // ── Check-in / check-out / no-show ─────────────────────────────────────────────────

    public record RoomOption(Long id, String number, String floor, String roomTypeName, String status, boolean current) {
    }

    public record CheckInPreview(boolean allowed, List<String> blockers, List<String> warnings, LocalDate today,
                                 BigDecimal balance, BigDecimal depositPending, RoomOption room, List<RoomOption> rooms) {
    }

    public record GuestRegistration(
            @NotBlank(message = "Escribe el nombre") @Size(max = 201) String fullName,
            boolean minor,
            IdType idType,
            @Size(max = 40) @Pattern(regexp = "^$|^[A-Za-z0-9 -]{3,40}$", message = "Número de identificación inválido") String idNumber) {
    }

    public record CheckInRequest(
            Long roomId,
            @Valid @Size(max = 20) List<GuestRegistration> guests,
            boolean identityVerified,
            @Size(max = 1000) String notes,
            boolean acceptPendingBalance,
            boolean overrideRoomStatus) {
    }

    public record CheckOutRequest(@Size(max = 1000) String notes, boolean allowPendingBalance, @Size(max = 500) String reason) {
    }

    public record NoShowRequest(boolean waivePenalty, @Size(max = 500) String reason) {
    }

    // ── Caja ───────────────────────────────────────────────────────────────────────────

    public record OpenCashRequest(
            @NotNull(message = "Indica el fondo inicial") @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal openingFloat,
            @Size(max = 500) String notes) {
    }

    public record CashAdjustRequest(
            @NotNull CashMovementType type,
            @NotNull @DecimalMin(value = "0.01", message = "El importe debe ser mayor a cero") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank(message = "Indica el motivo") @Size(max = 300) String reason) {

        @AssertTrue(message = "Solo se registran retiros o depósitos de efectivo")
        public boolean isAdjustment() {
            return type == null || type == CashMovementType.WITHDRAWAL || type == CashMovementType.DEPOSIT;
        }
    }

    public record CloseCashRequest(
            @NotNull(message = "Indica el efectivo contado") @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal countedCash,
            @Size(max = 500) String notes) {
    }

    public record CashSummary(BigDecimal openingFloat, BigDecimal cashIn, BigDecimal cashOut, BigDecimal cardIn,
                              BigDecimal cardOut, BigDecimal transferIn, BigDecimal transferOut, BigDecimal withdrawals,
                              BigDecimal deposits, BigDecimal expectedCash, int movements) {
    }

    public record CashMovementView(Long id, CashMovementType type, PaymentMethod method, Direction direction,
                                   BigDecimal amount, Long paymentId, Long reservationId, String folio, String reason,
                                   String userName, Instant createdAt) {
    }

    public record CashSessionView(Long id, Long userId, String userName, CashSessionStatus status, BigDecimal openingFloat,
                                 String openingNotes, Instant openedAt, Instant closedAt, String closedByName,
                                 BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference, String closingNotes,
                                 CashSummary summary, List<CashMovementView> movements) {
    }

    public record CashSessionRow(Long id, String userName, CashSessionStatus status, BigDecimal openingFloat,
                                 Instant openedAt, Instant closedAt, BigDecimal expectedCash, BigDecimal countedCash,
                                 BigDecimal difference) {
    }

    // ── Pagos (consulta y conciliación) ────────────────────────────────────────────────

    public record PaymentRow(Long id, Long reservationId, String folio, String guestName, PaymentKind kind,
                             PaymentMethod method, PaymentStatus status, BigDecimal amount, String reference,
                             String cardLast4, String createdByName, Instant createdAt, Instant completedAt) {
    }

    public record MethodTotal(PaymentMethod method, BigDecimal received, BigDecimal returned, BigDecimal net, long count) {
    }

    public record PaymentsReport(List<PaymentRow> content, int page, int totalPages, long totalElements,
                                 List<MethodTotal> totals, BigDecimal net) {
    }

    public record ReconciliationItem(Long reservationId, String folio, String guestName, ReservationStatus status,
                                     LocalDate checkIn, BigDecimal amountDue, BigDecimal paid, BigDecimal balance,
                                     String issue) {
    }

    // ── Configuración de cobro ─────────────────────────────────────────────────────────

    public record PaymentSettingsRequest(
            boolean cashEnabled,
            boolean cardEnabled,
            boolean transferEnabled,
            boolean onlineEnabled,
            boolean sandbox,
            @Pattern(regexp = "^$|^[A-Za-z0-9]{6,40}$", message = "ID de comercio inválido") String merchantId,
            /** null o vacío = conservar la llave guardada. */
            @Size(max = 200) @Pattern(regexp = "^$|^sk_[A-Za-z0-9]{10,100}$", message = "La llave privada de Openpay empieza con sk_") String privateKey,
            @Size(max = 100) @Pattern(regexp = "^$|^pk_[A-Za-z0-9]{10,90}$", message = "La llave pública de Openpay empieza con pk_") String publicKey,
            @Size(max = 1000) String transferInstructions) {
    }

    public record PaymentSettingsResponse(boolean cashEnabled, boolean cardEnabled, boolean transferEnabled,
                                          boolean onlineEnabled, boolean sandbox, String provider, String merchantId,
                                          boolean privateKeySet, String publicKey, String webhookUrl, String webhookUser,
                                          boolean webhookPasswordSet, String webhookVerification,
                                          String transferInstructions, boolean onlineUsable) {
    }

    public record WebhookCredentials(String url, String user, String password) {
    }

    // ── Portal público ─────────────────────────────────────────────────────────────────

    public record PublicPaymentOptions(boolean cash, boolean card, boolean transfer, boolean online, boolean sandbox,
                                       String transferInstructions) {
    }

    public record PublicPayRequest(@NotNull @Pattern(regexp = "DEPOSIT|BALANCE", message = "Opción de pago inválida") String purpose) {
    }

    public record PublicPaymentStart(String url, BigDecimal amount, String reference) {
    }

    public record PublicPaymentStatus(PaymentStatus status, BigDecimal amount, String currency, String folio,
                                      String hotelName, String failureReason, ReservationStatus reservationStatus,
                                      boolean sandbox) {
    }
}
