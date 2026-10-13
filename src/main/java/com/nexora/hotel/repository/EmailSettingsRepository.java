package com.nexora.hotel.repository;

import com.nexora.hotel.entity.EmailSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailSettingsRepository extends JpaRepository<EmailSettings, Long> {

    Optional<EmailSettings> findFirstBy();
}
