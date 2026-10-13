package com.nexora.hotel.repository;

import com.nexora.hotel.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Solo lectura e inserción: la tabla rechaza UPDATE/DELETE a nivel de base de datos. */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
