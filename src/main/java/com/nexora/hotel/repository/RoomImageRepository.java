package com.nexora.hotel.repository;

import com.nexora.hotel.entity.RoomImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoomImageRepository extends JpaRepository<RoomImage, Long> {

    List<RoomImage> findByRoomTypeIdOrderBySortOrderAscIdAsc(Long roomTypeId);

    List<RoomImage> findByRoomIdOrderBySortOrderAscIdAsc(Long roomId);

    List<RoomImage> findByRoomTypeIdInOrderBySortOrderAscIdAsc(Collection<Long> roomTypeIds);

    long countByRoomTypeId(Long roomTypeId);

    long countByRoomId(Long roomId);
}
