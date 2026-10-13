package com.nexora.hotel.controller.admin;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.AmenityRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.AmenityResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.BlockRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.BlockResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.HousekeepingEntry;
import com.nexora.hotel.dto.catalog.InventoryDtos.ImageResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.ReleaseRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.ReorderRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomTypeRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomTypeResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.StatusRequest;
import com.nexora.hotel.dto.request.SetActiveRequest;
import com.nexora.hotel.service.RoomCatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Administración · Habitaciones")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final RoomCatalogService service;

    // ── Amenidades ─────────────────────────────────────────────────────────────────────

    @GetMapping("/amenities")
    @PreAuthorize("hasAuthority('ROOMS:VIEW')")
    public ApiResponse<List<AmenityResponse>> amenities() {
        return ApiResponse.ok(service.amenities());
    }

    @PostMapping("/amenities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<AmenityResponse> createAmenity(@Valid @RequestBody AmenityRequest request) {
        return ApiResponse.ok(service.createAmenity(request), "Amenidad creada");
    }

    @PutMapping("/amenities/{id}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<AmenityResponse> updateAmenity(@PathVariable Long id, @Valid @RequestBody AmenityRequest request) {
        return ApiResponse.ok(service.updateAmenity(id, request), "Amenidad actualizada");
    }

    @DeleteMapping("/amenities/{id}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<Void> deleteAmenity(@PathVariable Long id) {
        service.deleteAmenity(id);
        return ApiResponse.ok(null, "Amenidad eliminada");
    }

    // ── Tipos ──────────────────────────────────────────────────────────────────────────

    @GetMapping("/room-types")
    @PreAuthorize("hasAnyAuthority('ROOMS:VIEW', 'RATES:VIEW')")
    public ApiResponse<List<RoomTypeResponse>> roomTypes() {
        return ApiResponse.ok(service.roomTypes());
    }

    @PostMapping("/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROOMS:CREATE')")
    public ApiResponse<RoomTypeResponse> createRoomType(@Valid @RequestBody RoomTypeRequest request) {
        return ApiResponse.ok(service.createRoomType(request), "Tipo de habitación creado");
    }

    @PutMapping("/room-types/{id}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<RoomTypeResponse> updateRoomType(@PathVariable Long id, @Valid @RequestBody RoomTypeRequest request) {
        return ApiResponse.ok(service.updateRoomType(id, request), "Tipo de habitación actualizado");
    }

    @PatchMapping("/room-types/{id}/active")
    @PreAuthorize("hasAuthority('ROOMS:DELETE')")
    public ApiResponse<RoomTypeResponse> setRoomTypeActive(@PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return ApiResponse.ok(service.setRoomTypeActive(id, request.active()));
    }

    @PostMapping(value = "/room-types/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> addTypeImages(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(service.addImages("TYPE", id, files), "Fotografías agregadas");
    }

    @PutMapping("/room-types/{id}/images/order")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> reorderTypeImages(@PathVariable Long id, @Valid @RequestBody ReorderRequest request) {
        return ApiResponse.ok(service.reorderImages("TYPE", id, request.imageIds()));
    }

    @DeleteMapping("/room-types/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> deleteTypeImage(@PathVariable Long id, @PathVariable Long imageId) {
        return ApiResponse.ok(service.deleteImage("TYPE", id, imageId), "Fotografía eliminada");
    }

    // ── Habitaciones ───────────────────────────────────────────────────────────────────

    @GetMapping("/rooms")
    @PreAuthorize("hasAnyAuthority('ROOMS:VIEW', 'HOUSEKEEPING:VIEW')")
    public ApiResponse<List<RoomResponse>> rooms() {
        return ApiResponse.ok(service.rooms());
    }

    @PostMapping("/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROOMS:CREATE')")
    public ApiResponse<RoomResponse> createRoom(@Valid @RequestBody RoomRequest request) {
        return ApiResponse.ok(service.createRoom(request), "Habitación creada");
    }

    @PutMapping("/rooms/{id}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<RoomResponse> updateRoom(@PathVariable Long id, @Valid @RequestBody RoomRequest request) {
        return ApiResponse.ok(service.updateRoom(id, request), "Habitación actualizada");
    }

    @PatchMapping("/rooms/{id}/active")
    @PreAuthorize("hasAuthority('ROOMS:DELETE')")
    public ApiResponse<RoomResponse> setRoomActive(@PathVariable Long id, @Valid @RequestBody SetActiveRequest request) {
        return ApiResponse.ok(service.setRoomActive(id, request.active()));
    }

    @PatchMapping("/rooms/{id}/status")
    @PreAuthorize("hasAnyAuthority('HOUSEKEEPING:UPDATE', 'ROOMS:UPDATE')")
    public ApiResponse<RoomResponse> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ApiResponse.ok(service.changeStatus(id, request), "Estado actualizado");
    }

    @GetMapping("/rooms/{id}/status-history")
    @PreAuthorize("hasAnyAuthority('ROOMS:VIEW', 'HOUSEKEEPING:VIEW')")
    public ApiResponse<List<HousekeepingEntry>> statusHistory(@PathVariable Long id) {
        return ApiResponse.ok(service.statusHistory(id));
    }

    @PostMapping(value = "/rooms/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> addRoomImages(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return ApiResponse.ok(service.addImages("ROOM", id, files), "Fotografías agregadas");
    }

    @PutMapping("/rooms/{id}/images/order")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> reorderRoomImages(@PathVariable Long id, @Valid @RequestBody ReorderRequest request) {
        return ApiResponse.ok(service.reorderImages("ROOM", id, request.imageIds()));
    }

    @DeleteMapping("/rooms/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('ROOMS:UPDATE')")
    public ApiResponse<List<ImageResponse>> deleteRoomImage(@PathVariable Long id, @PathVariable Long imageId) {
        return ApiResponse.ok(service.deleteImage("ROOM", id, imageId), "Fotografía eliminada");
    }

    // ── Bloqueos ───────────────────────────────────────────────────────────────────────

    @GetMapping("/rooms/{id}/blocks")
    @PreAuthorize("hasAuthority('ROOMS:VIEW')")
    public ApiResponse<List<BlockResponse>> blocks(@PathVariable Long id) {
        return ApiResponse.ok(service.blocks(id));
    }

    @PostMapping("/rooms/{id}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROOMS:BLOCK')")
    public ApiResponse<BlockResponse> block(@PathVariable Long id, @Valid @RequestBody BlockRequest request) {
        return ApiResponse.ok(service.block(id, request), "Habitación bloqueada");
    }

    @PostMapping("/rooms/{id}/blocks/{blockId}/release")
    @PreAuthorize("hasAuthority('ROOMS:RELEASE_BLOCK')")
    public ApiResponse<BlockResponse> release(@PathVariable Long id, @PathVariable Long blockId,
                                              @Valid @RequestBody(required = false) ReleaseRequest request) {
        return ApiResponse.ok(service.release(id, blockId, request == null ? null : request.notes()), "Bloqueo liberado");
    }
}
