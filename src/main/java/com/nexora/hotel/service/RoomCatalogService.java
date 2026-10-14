package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.catalog.InventoryDtos.AmenityRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.AmenityResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.BlockRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.BlockResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.HousekeepingEntry;
import com.nexora.hotel.dto.catalog.InventoryDtos.ImageResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.PublicRoomType;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomStay;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomTypeRequest;
import com.nexora.hotel.dto.catalog.InventoryDtos.RoomTypeResponse;
import com.nexora.hotel.dto.catalog.InventoryDtos.StatusRequest;
import com.nexora.hotel.entity.Amenity;
import com.nexora.hotel.entity.MaintenanceRecord;
import com.nexora.hotel.entity.Room;
import com.nexora.hotel.entity.RoomImage;
import com.nexora.hotel.entity.RoomType;
import com.nexora.hotel.entity.StoredFile;
import com.nexora.hotel.enums.CatalogEnums.MaintenanceStatus;
import com.nexora.hotel.enums.CatalogEnums.RoomStatus;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.exception.ConflictException;
import com.nexora.hotel.exception.ResourceNotFoundException;
import com.nexora.hotel.notification.StaffNotificationType;
import com.nexora.hotel.notification.StaffNotifier;
import com.nexora.hotel.repository.AmenityRepository;
import com.nexora.hotel.repository.MaintenanceRecordRepository;
import com.nexora.hotel.repository.OccupancyRepository;
import com.nexora.hotel.repository.RoomImageRepository;
import com.nexora.hotel.repository.RoomRepository;
import com.nexora.hotel.repository.RoomTypeRepository;
import com.nexora.hotel.security.AuthPrincipal;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.storage.MediaUrls;
import com.nexora.hotel.storage.StorageService;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inventario del hotel actual: amenidades, tipos de habitación, habitaciones, fotografías, estado operativo y
 * bloqueos de mantenimiento. Los tipos no dependen de valores fijos en el código: cada hotel define los suyos.
 */
@Service
@RequiredArgsConstructor
public class RoomCatalogService {

    public static final int MAX_IMAGES = 20;

    private final AmenityRepository amenityRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final RoomRepository roomRepository;
    private final RoomImageRepository imageRepository;
    private final MaintenanceRecordRepository maintenanceRepository;
    private final OccupancyRepository occupancyRepository;
    private final StorageService storage;
    private final MediaUrls mediaUrls;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;
    private final StaffNotifier staffNotifier;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ── Amenidades ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AmenityResponse> amenities() {
        return amenityRepository.findByActiveTrueOrderByNameAsc().stream().map(RoomCatalogService::toDto).toList();
    }

    @Transactional
    public AmenityResponse createAmenity(AmenityRequest r) {
        if (amenityRepository.existsByNameIgnoreCase(r.name().trim())) {
            throw new ConflictException("Ya existe una amenidad con ese nombre");
        }
        Amenity a = amenityRepository.save(Amenity.builder().name(r.name().trim()).icon(r.icon()).build());
        auditService.record(AuditEntry.success("AMENITY_CREATED", "Amenity", a.getId()).after(Map.of("name", a.getName())).build());
        return toDto(a);
    }

    @Transactional
    public AmenityResponse updateAmenity(Long id, AmenityRequest r) {
        Amenity a = amenityRepository.findById(id).filter(Amenity::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Amenidad no encontrada"));
        if (amenityRepository.existsByNameIgnoreCaseAndIdNot(r.name().trim(), id)) {
            throw new ConflictException("Ya existe una amenidad con ese nombre");
        }
        a.setName(r.name().trim());
        a.setIcon(r.icon());
        return toDto(a);
    }

    @Transactional
    public void deleteAmenity(Long id) {
        Amenity a = amenityRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Amenidad no encontrada"));
        jdbc.update("DELETE FROM room_type_amenities WHERE amenity_id = ?", id);
        a.setActive(false);
        auditService.record(AuditEntry.success("AMENITY_DELETED", "Amenity", id).before(Map.of("name", a.getName())).build());
    }

    // ── Tipos de habitación ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomTypeResponse> roomTypes() {
        List<RoomType> types = roomTypeRepository.findAllByOrderBySortOrderAscNameAsc();
        Map<Long, List<ImageResponse>> images = typeImages(types.stream().map(RoomType::getId).toList());
        Map<Long, Long> counts = new HashMap<>();
        roomRepository.findAllByOrderByFloorAscSortOrderAscNumberAsc().stream().filter(Room::isActive)
                .forEach(r -> counts.merge(r.getRoomType().getId(), 1L, Long::sum));
        return types.stream().map(t -> toDto(t, counts.getOrDefault(t.getId(), 0L), images.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional
    public RoomTypeResponse createRoomType(RoomTypeRequest r) {
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (roomTypeRepository.existsByCode(code)) {
            throw new ConflictException("Ya existe un tipo de habitación con la clave " + code);
        }
        RoomType t = RoomType.builder().code(code).build();
        apply(t, r);
        t = roomTypeRepository.save(t);
        auditService.record(AuditEntry.success("ROOM_TYPE_CREATED", "RoomType", t.getId())
                .after(Map.of("code", code, "name", t.getName(), "basePrice", t.getBasePrice())).build());
        return toDto(t, 0, List.of());
    }

    @Transactional
    public RoomTypeResponse updateRoomType(Long id, RoomTypeRequest r) {
        RoomType t = findType(id);
        String code = r.code().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(t.getCode()) && roomTypeRepository.existsByCode(code)) {
            throw new ConflictException("Ya existe un tipo de habitación con la clave " + code);
        }
        Map<String, Object> before = Map.of("name", t.getName(), "basePrice", t.getBasePrice(),
                "maxOccupancy", t.getMaxOccupancy());
        t.setCode(code);
        apply(t, r);
        auditService.record(AuditEntry.success("ROOM_TYPE_UPDATED", "RoomType", id).before(before)
                .after(Map.of("name", t.getName(), "basePrice", t.getBasePrice(), "maxOccupancy", t.getMaxOccupancy()))
                .build());
        return toDto(t, roomRepository.countByRoomTypeIdAndActiveTrue(id), images("TYPE", id));
    }

    @Transactional
    public RoomTypeResponse setRoomTypeActive(Long id, boolean active) {
        RoomType t = findType(id);
        if (!active && roomRepository.countByRoomTypeIdAndActiveTrue(id) > 0) {
            throw new ConflictException("Hay habitaciones activas de este tipo. Desactívalas o cámbialas de tipo primero.");
        }
        t.setActive(active);
        auditService.record(AuditEntry.success(active ? "ROOM_TYPE_ACTIVATED" : "ROOM_TYPE_DEACTIVATED", "RoomType", id).build());
        return toDto(t, roomRepository.countByRoomTypeIdAndActiveTrue(id), images("TYPE", id));
    }

    private void apply(RoomType t, RoomTypeRequest r) {
        t.setName(r.name().trim());
        t.setDescription(blank(r.description()));
        t.setBasePrice(r.basePrice());
        t.setMaxAdults(r.maxAdults());
        t.setMaxChildren(r.maxChildren());
        t.setMaxOccupancy(r.maxOccupancy());
        t.setIncludedOccupancy(r.includedOccupancy());
        t.setExtraAdultFee(r.extraAdultFee());
        t.setExtraChildFee(r.extraChildFee());
        t.setBedCount(r.bedCount());
        t.setBedType(r.bedType());
        t.setSizeM2(r.sizeM2());
        t.setSortOrder(r.sortOrder());
        Set<Long> ids = r.amenityIds() == null ? Set.of() : r.amenityIds();
        List<Amenity> amenities = amenityRepository.findAllById(ids).stream().filter(Amenity::isActive).toList();
        if (amenities.size() != ids.size()) {
            throw new BusinessException("Alguna amenidad no existe");
        }
        t.getAmenities().clear();
        t.getAmenities().addAll(amenities);
    }

    // ── Habitaciones ───────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RoomResponse> rooms() {
        List<Room> rooms = roomRepository.findAllByOrderByFloorAscSortOrderAscNumberAsc();
        Map<Long, List<BlockResponse>> blocks = maintenanceRepository.findAll().stream()
                .filter(m -> m.getStatus() == MaintenanceStatus.OPEN && m.getEndsOn().isAfter(today()))
                .collect(Collectors.groupingBy(MaintenanceRecord::getRoomId,
                        Collectors.mapping(RoomCatalogService::toDto, Collectors.toList())));
        Map<Long, RoomStay> current = new HashMap<>();
        Map<Long, RoomStay> next = new HashMap<>();
        loadStays(current, next);
        return rooms.stream().map(r -> toDto(r, images("ROOM", r.getId()), blocks.getOrDefault(r.getId(), List.of()),
                current.get(r.getId()), next.get(r.getId()))).toList();
    }

    /**
     * Por habitación: el huésped en casa y la siguiente llegada (de hoy en adelante). Una sola consulta para todo
     * el hotel, ordenada por llegada: la primera fila de cada habitación es la más próxima.
     */
    private void loadStays(Map<Long, RoomStay> current, Map<Long, RoomStay> next) {
        boolean names = SecurityUtils.requirePrincipal().hasPermission("RESERVATIONS:VIEW");
        jdbc.query("""
                SELECT rr.room_id, r.id, r.folio, r.contact_name, r.check_in, r.check_out, r.adults + r.children AS guests,
                       r.status = 'IN_HOUSE' AS in_house
                FROM reservations r JOIN reservation_rooms rr ON rr.reservation_id = r.id
                WHERE r.hotel_id = ? AND (r.status = 'IN_HOUSE'
                      OR (r.status IN ('CONFIRMED','PENDING','PENDING_PAYMENT') AND r.check_in >= ?))
                ORDER BY r.check_in, r.id""", (RowCallbackHandler) rs -> {
            RoomStay stay = new RoomStay(names ? rs.getLong("id") : null, names ? rs.getString("folio") : null,
                    names ? rs.getString("contact_name") : null, rs.getDate("check_in").toLocalDate(),
                    rs.getDate("check_out").toLocalDate(), rs.getInt("guests"));
            (rs.getBoolean("in_house") ? current : next).putIfAbsent(rs.getLong("room_id"), stay);
        }, TenantContext.requireHotelId(), today());
    }

    @Transactional
    public RoomResponse createRoom(RoomRequest r) {
        if (roomRepository.existsByNumberIgnoreCase(r.number().trim())) {
            throw new ConflictException("Ya existe la habitación " + r.number().trim());
        }
        Room room = Room.builder().roomType(activeType(r.roomTypeId())).build();
        apply(room, r);
        room = roomRepository.save(room);
        auditService.record(AuditEntry.success("ROOM_CREATED", "Room", room.getId())
                .after(Map.of("number", room.getNumber(), "type", room.getRoomType().getName())).build());
        return toDto(room, List.of(), List.of());
    }

    @Transactional
    public RoomResponse updateRoom(Long id, RoomRequest r) {
        Room room = findRoom(id);
        if (!room.getNumber().equalsIgnoreCase(r.number().trim()) && roomRepository.existsByNumberIgnoreCase(r.number().trim())) {
            throw new ConflictException("Ya existe la habitación " + r.number().trim());
        }
        Map<String, Object> before = Map.of("number", room.getNumber(), "type", room.getRoomType().getName());
        if (!room.getRoomType().getId().equals(r.roomTypeId())) {
            room.setRoomType(activeType(r.roomTypeId()));
        }
        apply(room, r);
        auditService.record(AuditEntry.success("ROOM_UPDATED", "Room", id).before(before)
                .after(Map.of("number", room.getNumber(), "type", room.getRoomType().getName())).build());
        return toDto(room, images("ROOM", id), openBlocks(id));
    }

    /** No se desactiva una habitación con reservaciones vigentes o futuras sin resolverlas antes. */
    @Transactional
    public RoomResponse setRoomActive(Long id, boolean active) {
        Room room = findRoom(id);
        if (!active && occupancyRepository.futureCommitments(id, today()) > 0) {
            throw new ConflictException("La habitación tiene reservaciones vigentes o futuras. Reubícalas antes de desactivarla.");
        }
        if (active && !room.getRoomType().isActive()) {
            throw new BusinessException("Activa primero el tipo de habitación " + room.getRoomType().getName());
        }
        room.setActive(active);
        auditService.record(AuditEntry.success(active ? "ROOM_ACTIVATED" : "ROOM_DEACTIVATED", "Room", id).build());
        return toDto(room, images("ROOM", id), openBlocks(id));
    }

    /**
     * Estado operativo. "Ocupada" solo lo pone el check-in. Quien solo tiene permiso de limpieza puede alternar
     * entre disponible y en limpieza; mantenimiento y fuera de servicio requieren permiso de edición.
     */
    @Transactional
    public RoomResponse changeStatus(Long id, StatusRequest r) {
        AuthPrincipal actor = SecurityUtils.requirePrincipal();
        Room room = findRoom(id);
        if (r.status() == RoomStatus.OCCUPIED) {
            throw new BusinessException("El estado Ocupada se asigna automáticamente al registrar el check-in");
        }
        // Con un huésped en casa el estado no se cambia a mano: "En limpieza" es la CONSECUENCIA del check-out (que
        // cobra el saldo y cierra la estancia), no un atajo para hacerlo. Pasar de Ocupada a limpieza a mano queda
        // solo para corregir una habitación que se quedó marcada como ocupada sin nadie adentro.
        Integer inHouse = jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN reservation_rooms rr ON rr.reservation_id = r.id
                WHERE rr.room_id = ? AND r.hotel_id = ? AND r.status = 'IN_HOUSE'""", Integer.class, id, TenantContext.requireHotelId());
        if (inHouse != null && inHouse > 0) {
            throw new BusinessException("La habitación tiene un huésped en casa: registra su salida desde la reservación");
        }
        if (room.getOperationalStatus() == RoomStatus.OCCUPIED && r.status() != RoomStatus.CLEANING) {
            throw new BusinessException("Una habitación ocupada solo puede pasar a limpieza tras el check-out");
        }
        boolean housekeepingOnly = (r.status() == RoomStatus.AVAILABLE || r.status() == RoomStatus.CLEANING)
                && (room.getOperationalStatus() != RoomStatus.MAINTENANCE && room.getOperationalStatus() != RoomStatus.OUT_OF_SERVICE);
        if (!(housekeepingOnly ? actor.hasPermission("HOUSEKEEPING:UPDATE") || actor.hasPermission("ROOMS:UPDATE")
                : actor.hasPermission("ROOMS:UPDATE"))) {
            throw new AccessDeniedException("No tienes permiso para este cambio de estado");
        }
        RoomStatus from = room.getOperationalStatus();
        if (from != r.status()) {
            room.setOperationalStatus(r.status());
            jdbc.update("""
                    INSERT INTO housekeeping_logs (hotel_id, room_id, from_status, to_status, notes, user_id)
                    VALUES (?, ?, ?, ?, ?, ?)""", TenantContext.requireHotelId(), id, from.name(), r.status().name(),
                    blank(r.notes()), actor.userId());
            auditService.record(AuditEntry.success("ROOM_STATUS_CHANGED", "Room", id)
                    .before(Map.of("status", from.name())).after(Map.of("status", r.status().name()))
                    .detail(blank(r.notes())).build());
            if (r.status() == RoomStatus.AVAILABLE) {
                notifyReadyForArrival(room);
            }
        }
        return toDto(room, images("ROOM", id), openBlocks(id));
    }

    /** La habitación quedó lista y alguien llega hoy a ella: recepción ya puede hacer su check-in. */
    private void notifyReadyForArrival(Room room) {
        LocalDate today = LocalDate.now(clock.withZone(hotelDirectory.current().zoneId()));
        jdbc.query("""
                SELECT r.id, r.folio, r.contact_name FROM reservations r
                JOIN reservation_rooms rr ON rr.reservation_id = r.id
                WHERE rr.room_id = ? AND r.hotel_id = ? AND r.check_in = ? AND r.status IN ('CONFIRMED','PENDING','PENDING_PAYMENT')
                ORDER BY r.id LIMIT 1""", rs -> {
            long reservationId = rs.getLong("id");
            staffNotifier.notify(StaffNotifier.Notice.of(StaffNotificationType.ROOM_READY, "ROOM_READY:" + reservationId + ":" + room.getId())
                    .param("room", room.getNumber()).param("folio", rs.getString("folio")).param("guest", rs.getString("contact_name"))
                    .link(StaffNotifier.reservationLink(reservationId)).related("Reservation", reservationId));
        }, room.getId(), TenantContext.requireHotelId(), today);
    }

    @Transactional(readOnly = true)
    public List<HousekeepingEntry> statusHistory(Long id) {
        findRoom(id);
        return jdbc.query("""
                SELECT h.from_status, h.to_status, h.notes, u.first_name || ' ' || u.last_name, h.created_at
                FROM housekeeping_logs h LEFT JOIN users u ON u.id = h.user_id
                WHERE h.room_id = ? AND h.hotel_id = ? ORDER BY h.created_at DESC LIMIT 50""",
                (rs, i) -> new HousekeepingEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), id, TenantContext.requireHotelId());
    }

    private void apply(Room room, RoomRequest r) {
        room.setNumber(r.number().trim().toUpperCase(Locale.ROOT));
        room.setName(blank(r.name()));
        room.setFloor(blank(r.floor()));
        room.setDescription(blank(r.description()));
        room.setInternalNotes(blank(r.internalNotes()));
        room.setSortOrder(r.sortOrder());
    }

    // ── Bloqueos de mantenimiento ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BlockResponse> blocks(Long roomId) {
        findRoom(roomId);
        return maintenanceRepository.findByRoomIdOrderByStartsOnDesc(roomId).stream().map(RoomCatalogService::toDto).toList();
    }

    /**
     * Bloquea [inicio, fin). La restricción EXCLUDE de {@code room_occupancies} impide bloquear sobre una
     * reservación u otro bloqueo, incluso si dos personas lo intentan al mismo tiempo.
     */
    @Transactional
    public BlockResponse block(Long roomId, BlockRequest r) {
        // Primero se bloquea la fila de la habitación: las operaciones concurrentes sobre ella esperan su turno y
        // la restricción EXCLUDE responde con un rechazo limpio en vez de un deadlock entre inserciones.
        Room room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResourceNotFoundException("Habitación no encontrada"));
        if (!room.isActive()) {
            throw new BusinessException("La habitación está desactivada");
        }
        if (r.endsOn().isBefore(today())) {
            throw new BusinessException("No se pueden bloquear fechas pasadas");
        }
        MaintenanceRecord record = maintenanceRepository.save(MaintenanceRecord.builder()
                .roomId(roomId).reason(r.reason().trim()).startsOn(r.startsOn()).endsOn(r.endsOn())
                .createdBy(SecurityUtils.currentPrincipal().map(AuthPrincipal::userId).orElse(null))
                .build());
        maintenanceRepository.flush();
        try {
            occupancyRepository.insertBlock(TenantContext.requireHotelId(), roomId, r.startsOn(), r.endsOn(), record.getId());
        } catch (DataIntegrityViolationException e) {
            if (isOverlap(e)) {
                throw new ConflictException("La habitación ya tiene una reservación o un bloqueo en esas fechas");
            }
            throw e;
        }
        auditService.record(AuditEntry.success("ROOM_BLOCKED", "Room", roomId)
                .after(Map.of("from", r.startsOn().toString(), "to", r.endsOn().toString(), "reason", record.getReason()))
                .build());
        return toDto(record);
    }

    @Transactional
    public BlockResponse release(Long roomId, Long blockId, String notes) {
        MaintenanceRecord record = maintenanceRepository.findById(blockId).filter(m -> m.getRoomId().equals(roomId))
                .orElseThrow(() -> new ResourceNotFoundException("Bloqueo no encontrado"));
        if (record.getStatus() == MaintenanceStatus.RELEASED) {
            throw new BusinessException("El bloqueo ya fue liberado");
        }
        occupancyRepository.deleteBlock(blockId);
        record.setStatus(MaintenanceStatus.RELEASED);
        record.setReleasedAt(clock.instant());
        record.setReleasedBy(SecurityUtils.currentPrincipal().map(AuthPrincipal::userId).orElse(null));
        record.setReleaseNotes(blank(notes));
        auditService.record(AuditEntry.success("ROOM_BLOCK_RELEASED", "Room", roomId).detail(blank(notes)).build());
        return toDto(record);
    }

    // ── Fotografías ────────────────────────────────────────────────────────────────────

    @Transactional
    public List<ImageResponse> addImages(String owner, Long ownerId, List<MultipartFile> files) {
        requireOwner(owner, ownerId);
        long current = "TYPE".equals(owner) ? imageRepository.countByRoomTypeId(ownerId) : imageRepository.countByRoomId(ownerId);
        if (files == null || files.isEmpty()) {
            throw new BusinessException("Selecciona al menos una imagen");
        }
        if (current + files.size() > MAX_IMAGES) {
            throw new BusinessException("Máximo " + MAX_IMAGES + " fotografías por " + ("TYPE".equals(owner) ? "tipo" : "habitación"));
        }
        int order = (int) current;
        for (MultipartFile file : files) {
            StoredFile stored = storage.storeImage(file, "TYPE".equals(owner) ? "room-types" : "rooms",
                    "TYPE".equals(owner) ? "RoomType" : "Room", ownerId);
            imageRepository.save(RoomImage.builder()
                    .roomTypeId("TYPE".equals(owner) ? ownerId : null)
                    .roomId("ROOM".equals(owner) ? ownerId : null)
                    .file(stored).sortOrder(order++).build());
        }
        auditService.record(AuditEntry.success("IMAGES_ADDED", "TYPE".equals(owner) ? "RoomType" : "Room", ownerId)
                .detail(files.size() + " imagen(es)").build());
        return images(owner, ownerId);
    }

    @Transactional
    public List<ImageResponse> reorderImages(String owner, Long ownerId, List<Long> imageIds) {
        requireOwner(owner, ownerId);
        List<RoomImage> current = ownedImages(owner, ownerId);
        Set<Long> currentIds = current.stream().map(RoomImage::getId).collect(Collectors.toSet());
        if (!currentIds.equals(new HashSet<>(imageIds)) || imageIds.size() != currentIds.size()) {
            throw new BusinessException("El orden enviado no coincide con las fotografías actuales");
        }
        Map<Long, RoomImage> byId = current.stream().collect(Collectors.toMap(RoomImage::getId, i -> i));
        for (int i = 0; i < imageIds.size(); i++) {
            byId.get(imageIds.get(i)).setSortOrder(i);
        }
        return images(owner, ownerId);
    }

    @Transactional
    public List<ImageResponse> deleteImage(String owner, Long ownerId, Long imageId) {
        requireOwner(owner, ownerId);
        RoomImage image = ownedImages(owner, ownerId).stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Fotografía no encontrada"));
        imageRepository.delete(image);
        storage.delete(image.getFile());
        auditService.record(AuditEntry.success("IMAGE_DELETED", "TYPE".equals(owner) ? "RoomType" : "Room", ownerId).build());
        return images(owner, ownerId);
    }

    // ── Público ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PublicRoomType> publicRoomTypes() {
        hotelDirectory.current();
        List<RoomType> types = roomTypeRepository.findByActiveTrueOrderBySortOrderAscNameAsc();
        Map<Long, Long> activeRooms = new HashMap<>();
        roomRepository.findAllByOrderByFloorAscSortOrderAscNumberAsc().stream().filter(Room::isActive)
                .forEach(r -> activeRooms.merge(r.getRoomType().getId(), 1L, Long::sum));
        Map<Long, List<ImageResponse>> images = typeImages(types.stream().map(RoomType::getId).toList());
        return types.stream().filter(t -> activeRooms.getOrDefault(t.getId(), 0L) > 0)
                .map(t -> new PublicRoomType(t.getId(), t.getName(), t.getDescription(), t.getBasePrice(), t.getMaxAdults(),
                        t.getMaxChildren(), t.getMaxOccupancy(), t.getBedCount(), t.getBedType(), t.getSizeM2(),
                        t.getAmenities().stream().filter(Amenity::isActive).sorted(Comparator.comparing(Amenity::getName))
                                .map(RoomCatalogService::toDto).toList(),
                        images.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    // ── Apoyo ──────────────────────────────────────────────────────────────────────────

    private RoomType findType(Long id) {
        return roomTypeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Tipo de habitación no encontrado"));
    }

    private RoomType activeType(Long id) {
        RoomType t = findType(id);
        if (!t.isActive()) {
            throw new BusinessException("El tipo de habitación está desactivado");
        }
        return t;
    }

    private Room findRoom(Long id) {
        return roomRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Habitación no encontrada"));
    }

    private void requireOwner(String owner, Long id) {
        if ("TYPE".equals(owner)) {
            findType(id);
        } else if ("ROOM".equals(owner)) {
            findRoom(id);
        } else {
            throw new IllegalArgumentException("Propietario inválido");
        }
    }

    private List<RoomImage> ownedImages(String owner, Long id) {
        return "TYPE".equals(owner) ? imageRepository.findByRoomTypeIdOrderBySortOrderAscIdAsc(id)
                : imageRepository.findByRoomIdOrderBySortOrderAscIdAsc(id);
    }

    private List<ImageResponse> images(String owner, Long id) {
        return ownedImages(owner, id).stream().map(this::toDto).toList();
    }

    private Map<Long, List<ImageResponse>> typeImages(List<Long> typeIds) {
        if (typeIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findByRoomTypeIdInOrderBySortOrderAscIdAsc(typeIds).stream()
                .collect(Collectors.groupingBy(RoomImage::getRoomTypeId, Collectors.mapping(this::toDto, Collectors.toList())));
    }

    private List<BlockResponse> openBlocks(Long roomId) {
        return maintenanceRepository.findByRoomIdOrderByStartsOnDesc(roomId).stream()
                .filter(m -> m.getStatus() == MaintenanceStatus.OPEN && m.getEndsOn().isAfter(today()))
                .map(RoomCatalogService::toDto).toList();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(hotelDirectory.current().zoneId()));
    }

    private ImageResponse toDto(RoomImage i) {
        return new ImageResponse(i.getId(), mediaUrls.of(i.getFile()), i.getSortOrder(), i.getAltText(),
                i.getFile().getWidth(), i.getFile().getHeight());
    }

    private static AmenityResponse toDto(Amenity a) {
        return new AmenityResponse(a.getId(), a.getName(), a.getIcon(), a.isActive());
    }

    private static RoomTypeResponse toDto(RoomType t, long rooms, List<ImageResponse> images) {
        return new RoomTypeResponse(t.getId(), t.getCode(), t.getName(), t.getDescription(), t.getBasePrice(),
                t.getMaxAdults(), t.getMaxChildren(), t.getMaxOccupancy(), t.getIncludedOccupancy(), t.getExtraAdultFee(),
                t.getExtraChildFee(), t.getBedCount(), t.getBedType(), t.getSizeM2(), t.getSortOrder(), t.isActive(), rooms,
                t.getAmenities().stream().filter(Amenity::isActive).sorted(Comparator.comparing(Amenity::getName))
                        .map(RoomCatalogService::toDto).toList(),
                images);
    }

    private static RoomResponse toDto(Room r, List<ImageResponse> images, List<BlockResponse> blocks) {
        return toDto(r, images, blocks, null, null);
    }

    private static RoomResponse toDto(Room r, List<ImageResponse> images, List<BlockResponse> blocks, RoomStay current,
                                      RoomStay next) {
        return new RoomResponse(r.getId(), r.getNumber(), r.getName(), r.getFloor(), r.getDescription(), r.getInternalNotes(),
                r.getOperationalStatus(), r.isActive(), r.getSortOrder(), r.getVersion(), r.getRoomType().getId(),
                r.getRoomType().getName(), images, blocks, current, next);
    }

    private static BlockResponse toDto(MaintenanceRecord m) {
        return new BlockResponse(m.getId(), m.getStartsOn(), m.getEndsOn(), m.getReason(), m.getStatus(), m.getCreatedAt(),
                m.getReleasedAt(), m.getReleaseNotes());
    }

    private static boolean isOverlap(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
