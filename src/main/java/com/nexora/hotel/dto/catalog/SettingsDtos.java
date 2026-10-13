package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.enums.CatalogEnums.PenaltyType;
import com.nexora.hotel.validation.HexColor;
import com.nexora.hotel.validation.MxPhone;
import com.nexora.hotel.validation.MxPostalCode;
import com.nexora.hotel.validation.Rfc;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
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
import java.time.LocalTime;

/** DTOs de configuración del hotel: operación, identidad, tema y políticas. */
public final class SettingsDtos {

    private static final String URL = "^$|^https://[^\\s<>\"']{3,250}$";
    private static final String URL_MSG = "Debe ser una dirección https:// válida";

    private SettingsDtos() {
    }

    // ── Operación ──────────────────────────────────────────────────────────────────────

    public record OperationSettings(
            @NotNull LocalTime checkInTime,
            @NotNull LocalTime checkOutTime,
            @NotNull LocalTime receptionOpens,
            @NotNull LocalTime receptionCloses,
            boolean reception24h,
            @Min(0) @Max(720) int minBookingLeadHours,
            @Min(1) @Max(730) int maxBookingLeadDays,
            @Min(1) @Max(60) int minNights,
            @Min(1) @Max(365) int maxNights,
            @Min(1) @Max(50) int maxRoomsPerBooking,
            @Min(5) @Max(120) int holdMinutes,
            boolean allowSameDayBooking,
            @Min(1) @Max(168) int pendingConfirmationHours) {

        @AssertTrue(message = "Las noches máximas deben ser mayores o iguales a las mínimas")
        public boolean isNightsRangeValid() {
            return maxNights >= minNights;
        }
    }

    // ── Identidad ──────────────────────────────────────────────────────────────────────

    public record IdentityRequest(
            @NotBlank(message = "Escribe el nombre comercial") @Size(max = 150) String name,
            @Size(max = 160) String tagline,
            @Size(max = 2000) String description,
            @Size(max = 200) String legalName,
            @Rfc String rfc,
            @Size(max = 200) String street,
            @Size(max = 120) String neighborhood,
            @Size(max = 120) String city,
            @Size(max = 120) String state,
            @MxPostalCode String postalCode,
            @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitude,
            @MxPhone String phone,
            @MxPhone String whatsapp,
            @Email(message = "El correo no es válido") @Size(max = 150) String contactEmail,
            @Pattern(regexp = URL, message = URL_MSG) String website,
            @Pattern(regexp = URL, message = URL_MSG) String facebook,
            @Pattern(regexp = URL, message = URL_MSG) String instagram,
            @Pattern(regexp = URL, message = URL_MSG) String tiktok) {
    }

    /** Paleta del hotel. Solo #RRGGBB: nunca CSS ni valores arbitrarios. */
    public record ThemeRequest(
            @NotBlank @HexColor String primary,
            @NotBlank @HexColor String accent,
            @NotBlank @HexColor String sidebar,
            @NotBlank @Pattern(regexp = "^(light|dark|system)$", message = "Modo inválido") String defaultMode) {
    }

    public record IdentityResponse(String name, String slug, String tagline, String description, String legalName,
                                   String rfc, String street, String neighborhood, String city, String state,
                                   String postalCode, String country, BigDecimal latitude, BigDecimal longitude,
                                   String phone, String whatsapp, String contactEmail, String website,
                                   String facebook, String instagram, String tiktok,
                                   String logoUrl, String logoAltUrl, String faviconUrl, String heroUrl,
                                   ThemeResponse theme) {
    }

    public record ThemeResponse(String primary, String accent, String sidebar, String defaultMode) {
        public static final ThemeResponse DEFAULT = new ThemeResponse("#0d9488", "#c8a24a", "#0a0f1c", "system");
    }

    // ── Políticas ──────────────────────────────────────────────────────────────────────

    public record PolicyRequest(
            @Min(0) @Max(8760) int freeCancelHours,
            @NotNull PenaltyType lateCancelPenaltyType,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal lateCancelPenaltyValue,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal depositPercent,
            @Min(5) @Max(1440) int pendingPaymentExpiryMinutes,
            @NotNull PenaltyType noShowPenaltyType,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal noShowPenaltyValue,
            @Size(max = 2000) String childrenPolicy,
            boolean petsAllowed,
            @Size(max = 2000) String petsPolicy,
            @Size(max = 2000) String refundPolicy,
            @Size(max = 2000) String dateChangePolicy,
            @Size(max = 50000) String terms,
            @Size(max = 50000) String privacyNotice) {

        @AssertTrue(message = "Un porcentaje de penalización no puede superar 100")
        public boolean isPercentValid() {
            return (lateCancelPenaltyType != PenaltyType.PERCENT || lateCancelPenaltyValue == null
                    || lateCancelPenaltyValue.compareTo(new BigDecimal("100")) <= 0)
                    && (noShowPenaltyType != PenaltyType.PERCENT || noShowPenaltyValue == null
                    || noShowPenaltyValue.compareTo(new BigDecimal("100")) <= 0);
        }
    }

    public record PolicyResponse(int version, Instant createdAt, @Valid PolicyRequest values) {
    }

    // ── Vista pública ──────────────────────────────────────────────────────────────────

    public record PublicHotel(String slug, String name, String timezone, String currency, String tagline,
                              String description, String city, String state, String address, String phone,
                              String whatsapp, String contactEmail, String website, String facebook,
                              String instagram, String tiktok, BigDecimal latitude, BigDecimal longitude,
                              String logoUrl, String faviconUrl, String heroUrl, ThemeResponse theme,
                              LocalTime checkInTime, LocalTime checkOutTime, boolean petsAllowed) {
    }
}
