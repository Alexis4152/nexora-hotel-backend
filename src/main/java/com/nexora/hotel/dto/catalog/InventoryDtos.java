package com.nexora.hotel.dto.catalog;

import com.nexora.hotel.enums.CatalogEnums.MaintenanceStatus;
import com.nexora.hotel.enums.CatalogEnums.RoomStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** DTOs de amenidades, tipos de habitación, habitaciones, fotografías y bloqueos. */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    public record AmenityRequest(
            @NotBlank(message = "Escribe el nombre") @Size(max = 80) String name,
            @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,40}$", message = "Ícono inválido") String icon) {
    }

    public record AmenityResponse(Long id, String name, String icon, boolean active) {
    }

    public record ImageResponse(Long id, String url, int sortOrder, String altText, Integer width, Integer height) {
    }

    public record ReorderRequest(@NotEmpty List<Long> imageIds) {
    }

    public record RoomTypeRequest(
            @NotBlank(message = "Escribe una clave") @Pattern(regexp = "^[A-Za-z0-9_-]{2,20}$",
                    message = "Clave de 2 a 20 caracteres: letras, números, guion o guion bajo") String code,
            @NotBlank(message = "Escribe el nombre") @Size(max = 100) String name,
            @Size(max = 3000) String description,
            @NotNull(message = "Escribe el precio base") @DecimalMin(value = "1.00", message = "El precio debe ser mayor a 0")
            @DecimalMax("9999999.99") @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
            @Min(1) @Max(20) int maxAdults,
            @Min(0) @Max(20) int maxChildren,
            @Min(1) @Max(30) int maxOccupancy,
            @Min(1) @Max(30) int includedOccupancy,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal extraAdultFee,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal extraChildFee,
            @Min(1) @Max(10) int bedCount,
            @NotBlank @Pattern(regexp = "^(INDIVIDUAL|MATRIMONIAL|QUEEN|KING|LITERA|SOFA_CAMA)$", message = "Tipo de cama inválido") String bedType,
            @DecimalMin("1") @DecimalMax("9999") @Digits(integer = 4, fraction = 1) BigDecimal sizeM2,
            @Min(0) @Max(9999) int sortOrder,
            Set<Long> amenityIds) {

        @AssertTrue(message = "La capacidad máxima no puede superar adultos + menores")
        public boolean isCapacityValid() {
            return maxOccupancy <= maxAdults + maxChildren;
        }

        @AssertTrue(message = "La ocupación incluida no puede superar la capacidad máxima")
        public boolean isIncludedValid() {
            return includedOccupancy <= maxOccupancy;
        }
    }

    public record RoomTypeResponse(Long id, String code, String name, String description, BigDecimal basePrice,
                                   int maxAdults, int maxChildren, int maxOccupancy, int includedOccupancy,
                                   BigDecimal extraAdultFee, BigDecimal extraChildFee, int bedCount, String bedType,
                                   BigDecimal sizeM2, int sortOrder, boolean active, long roomCount,
                                   List<AmenityResponse> amenities, List<ImageResponse> images) {
    }

    public record RoomRequest(
            @NotNull(message = "Elige el tipo de habitación") Long roomTypeId,
            @NotBlank(message = "Escribe el número") @Pattern(regexp = "^[A-Za-z0-9-]{1,10}$",
                    message = "Número de 1 a 10 caracteres: letras, números o guion") String number,
            @Size(max = 100) String name,
            @Pattern(regexp = "^$|^[A-Za-z0-9-]{1,10}$", message = "Piso inválido") String floor,
            @Size(max = 2000) String description,
            @Size(max = 2000) String internalNotes,
            @Min(0) @Max(9999) int sortOrder) {
    }

    public record StatusRequest(@NotNull RoomStatus status, @Size(max = 500) String notes) {
    }

    public record RoomResponse(Long id, String number, String name, String floor, String description,
                               String internalNotes, RoomStatus status, boolean active, int sortOrder, long version,
                               Long roomTypeId, String roomTypeName, List<ImageResponse> images,
                               List<BlockResponse> activeBlocks) {
    }

    public record BlockRequest(
            @NotNull(message = "Indica la fecha de inicio") LocalDate startsOn,
            @NotNull(message = "Indica la fecha de fin") LocalDate endsOn,
            @NotBlank(message = "Describe el motivo") @Size(max = 500) String reason) {

        @AssertTrue(message = "La fecha de fin debe ser posterior a la de inicio")
        public boolean isRangeValid() {
            return startsOn == null || endsOn == null || endsOn.isAfter(startsOn);
        }
    }

    public record ReleaseRequest(@Size(max = 500) String notes) {
    }

    public record BlockResponse(Long id, LocalDate startsOn, LocalDate endsOn, String reason, MaintenanceStatus status,
                                Instant createdAt, Instant releasedAt, String releaseNotes) {
    }

    public record HousekeepingEntry(String fromStatus, String toStatus, String notes, String userName, Instant at) {
    }

    // ── Público ────────────────────────────────────────────────────────────────────────

    public record PublicRoomType(Long id, String name, String description, BigDecimal basePrice, int maxAdults,
                                 int maxChildren, int maxOccupancy, int bedCount, String bedType, BigDecimal sizeM2,
                                 List<AmenityResponse> amenities, List<ImageResponse> images) {
    }
}
