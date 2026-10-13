package com.nexora.hotel.repository;

import com.nexora.hotel.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByStorageKeyAndDeletedAtIsNull(String storageKey);
}
