package com.nexora.hotel.repository;

import com.nexora.hotel.entity.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByEventCode(String eventCode);

    List<NotificationTemplate> findAllByOrderByEventCodeAsc();
}
