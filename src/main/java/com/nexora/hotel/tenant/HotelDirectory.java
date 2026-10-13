package com.nexora.hotel.tenant;

import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.repository.HotelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Datos de los hoteles que se consultan en CADA request (resolver el slug del enlace, saber si está
 * activo, su zona horaria), en caché. Solo cambian desde el panel del SUPER_ADMIN, que llama a
 * {@link #evict()} al guardar. Caché local: válida para el despliegue de una sola instancia.
 */
@Component
@RequiredArgsConstructor
public class HotelDirectory {

    private final HotelRepository hotelRepository;

    private final Map<String, Hotel> bySlug = new ConcurrentHashMap<>();
    private final Map<Long, Hotel> byId = new ConcurrentHashMap<>();

    /** Id del hotel ACTIVO con ese slug, o null. */
    public Long activeIdBySlug(String slug) {
        Hotel hotel = bySlug.computeIfAbsent(slug, s -> hotelRepository.findBySlug(s).orElse(null));
        return hotel != null && hotel.isActive() ? hotel.getId() : null;
    }

    public boolean isActive(long hotelId) {
        Hotel hotel = get(hotelId);
        return hotel != null && hotel.isActive();
    }

    public Hotel get(long hotelId) {
        return byId.computeIfAbsent(hotelId, id -> hotelRepository.findById(id).orElse(null));
    }

    public Optional<Hotel> find(long hotelId) {
        return Optional.ofNullable(get(hotelId));
    }

    /** Hotel de la operación en curso (error de negocio si no hay uno). */
    public Hotel current() {
        Hotel hotel = get(TenantContext.requireHotelId());
        if (hotel == null) {
            throw new MissingHotelException();
        }
        return hotel;
    }

    public void evict() {
        bySlug.clear();
        byId.clear();
    }
}
