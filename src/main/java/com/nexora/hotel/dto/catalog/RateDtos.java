package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.enums.CatalogEnums.AdjustmentType;
import com.nexora.hotel.enums.CatalogEnums.ChargeMode;
import com.nexora.hotel.enums.CatalogEnums.RateRuleType;
import com.nexora.hotel.enums.CatalogEnums.SeasonKind;
import com.nexora.hotel.enums.CatalogEnums.TaxAppliesTo;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** DTOs de temporadas, reglas de tarifa, impuestos, servicios y simulación de precios. */
public final class RateDtos {

    private RateDtos() {
    }

    public record SeasonRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 80) String name,
            @NotNull SeasonKind kind,
            @NotNull(message = "Indica el inicio") LocalDate startsOn,
            @NotNull(message = "Indica el fin") LocalDate endsOn,
            boolean active) {

        @AssertTrue(message = "La fecha de fin no puede ser anterior al inicio")
        public boolean isRangeValid() {
            return startsOn == null || endsOn == null || !endsOn.isBefore(startsOn);
        }
    }

    public record SeasonResponse(Long id, String name, SeasonKind kind, LocalDate startsOn, LocalDate endsOn, boolean active) {
    }

    public record RateRuleRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 100) String name,
            Long roomTypeId,
            @NotNull RateRuleType ruleType,
            Long seasonId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Set<DayOfWeek> daysOfWeek,
            @NotNull AdjustmentType adjustmentType,
            @NotNull(message = "Escribe el valor") @DecimalMin("-9999999") @DecimalMax("9999999")
            @Digits(integer = 10, fraction = 2) BigDecimal adjustmentValue,
            @Min(1) @Max(60) Integer minNights,
            @Min(-100) @Max(100) int priority,
            boolean active) {

        @AssertTrue(message = "Las reglas por fecha o festivo requieren un rango válido")
        public boolean isDatesValid() {
            if (ruleType != RateRuleType.DATE_OVERRIDE && ruleType != RateRuleType.HOLIDAY) {
                return dateFrom == null || dateTo == null || !dateTo.isBefore(dateFrom);
            }
            return dateFrom != null && dateTo != null && !dateTo.isBefore(dateFrom);
        }

        @AssertTrue(message = "Las reglas de temporada requieren elegir la temporada")
        public boolean isSeasonValid() {
            return ruleType != RateRuleType.SEASON || seasonId != null;
        }

        @AssertTrue(message = "Las reglas de fin de semana requieren al menos un día")
        public boolean isDaysValid() {
            return ruleType != RateRuleType.WEEKEND || (daysOfWeek != null && !daysOfWeek.isEmpty());
        }

        @AssertTrue(message = "El valor no es válido para el tipo de ajuste")
        public boolean isAdjustmentValid() {
            if (adjustmentValue == null || adjustmentType == null) {
                return true;
            }
            return switch (adjustmentType) {
                case FIXED_PRICE -> adjustmentValue.signum() > 0;
                case PERCENT -> adjustmentValue.compareTo(new BigDecimal("-90")) >= 0
                        && adjustmentValue.compareTo(new BigDecimal("500")) <= 0;
                case AMOUNT -> true;
            };
        }
    }

    public record RateRuleResponse(Long id, String name, Long roomTypeId, String roomTypeName, RateRuleType ruleType,
                                   Long seasonId, String seasonName, LocalDate dateFrom, LocalDate dateTo,
                                   List<DayOfWeek> daysOfWeek, AdjustmentType adjustmentType, BigDecimal adjustmentValue,
                                   Integer minNights, int priority, boolean active) {
    }

    public record TaxRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 60) String name,
            @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal percent,
            @NotNull TaxAppliesTo appliesTo,
            boolean includedInPrice,
            boolean active) {
    }

    public record TaxResponse(Long id, String name, BigDecimal percent, TaxAppliesTo appliesTo, boolean includedInPrice,
                              boolean active) {
    }

    public record ServiceRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 100) String name,
            @Size(max = 1000) String description,
            @NotNull(message = "Escribe el precio") @DecimalMin("0") @DecimalMax("9999999.99")
            @Digits(integer = 10, fraction = 2) BigDecimal price,
            @NotNull ChargeMode chargeMode,
            boolean bookableOnline,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,40}$", message = "Ícono inválido") String icon,
            @Min(0) @Max(9999) int sortOrder,
            boolean active) {
    }

    public record ServiceResponse(Long id, String name, String description, BigDecimal price, ChargeMode chargeMode,
                                  boolean bookableOnline, String icon, int sortOrder, boolean active) {
    }

    public record QuoteRequest(
            @NotNull Long roomTypeId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @Min(1) @Max(20) int adults,
            @Min(0) @Max(20) int children) {

        @AssertTrue(message = "La salida debe ser posterior a la entrada (máximo 60 noches)")
        public boolean isRangeValid() {
            return checkIn == null || checkOut == null
                    || (checkOut.isAfter(checkIn) && !checkOut.isAfter(checkIn.plusDays(60)));
        }
    }
}
