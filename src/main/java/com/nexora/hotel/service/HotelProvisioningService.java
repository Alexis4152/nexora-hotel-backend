package com.nexora.hotel.service;

import com.nexora.hotel.dto.request.CreateHotelRequest;
import com.nexora.hotel.dto.request.UpdateHotelRequest;
import com.nexora.hotel.dto.response.HotelSummary;
import com.nexora.hotel.dto.response.ProvisionedHotelResponse;
import com.nexora.hotel.dto.response.TemporaryPasswordResponse;

import java.util.List;

/** Operaciones del SUPER_ADMIN de Nexora sobre los hoteles (tenants). */
public interface HotelProvisioningService {

    List<HotelSummary> list();

    /** Crea el hotel, siembra sus roles (ADMIN, RECEPTIONIST, GUEST) y su primer administrador. */
    ProvisionedHotelResponse create(CreateHotelRequest request);

    /** Igual que {@link #create} pero con contraseña conocida y sin cambio obligatorio (solo datos demo en dev). */
    ProvisionedHotelResponse createWithPassword(CreateHotelRequest request, String adminPassword);

    HotelSummary update(Long hotelId, UpdateHotelRequest request);

    /** Genera una contraseña temporal para el administrador principal del hotel (cuando el dueño la pierde). */
    TemporaryPasswordResponse resetAdminPassword(Long hotelId);
}
