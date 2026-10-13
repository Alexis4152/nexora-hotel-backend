package com.nexora.hotel.controller.publicapi;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.PublicRoomType;
import com.nexora.hotel.dto.catalog.RateDtos.ServiceResponse;
import com.nexora.hotel.dto.catalog.SettingsDtos.PublicHotel;
import com.nexora.hotel.dto.catalog.SettingsDtos.PolicyResponse;
import com.nexora.hotel.service.HotelSettingsService;
import com.nexora.hotel.service.RateService;
import com.nexora.hotel.service.RoomCatalogService;
import com.nexora.hotel.tenant.HotelDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Portal público del hotel identificado por {@code X-Hotel}. Solo datos públicos: nunca notas internas,
 * datos fiscales completos ni configuración sensible.
 */
@Tag(name = "Público")
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicHotelController {

    private final HotelSettingsService settingsService;
    private final RoomCatalogService roomCatalogService;
    private final RateService rateService;
    private final HotelDirectory hotelDirectory;

    @Operation(summary = "Identidad pública, tema y datos de contacto del hotel")
    @GetMapping("/hotel")
    public ApiResponse<PublicHotel> hotel() {
        hotelDirectory.current();
        return ApiResponse.ok(settingsService.publicView());
    }

    @Operation(summary = "Políticas vigentes: cancelación, términos y aviso de privacidad que el huésped acepta al reservar")
    @GetMapping("/policies")
    public ApiResponse<PolicyResponse> policies() {
        hotelDirectory.current();
        return ApiResponse.ok(settingsService.policies());
    }

    @Operation(summary = "Tipos de habitación activos con fotografías y precio base")
    @GetMapping("/room-types")
    public ApiResponse<List<PublicRoomType>> roomTypes() {
        return ApiResponse.ok(roomCatalogService.publicRoomTypes());
    }

    @Operation(summary = "Servicios adicionales que se pueden contratar en línea")
    @GetMapping("/services")
    public ApiResponse<List<ServiceResponse>> services() {
        hotelDirectory.current();
        return ApiResponse.ok(rateService.publicServices());
    }
}
