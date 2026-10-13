package com.nexora.hotel.entity;

import com.nexora.hotel.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Metadatos de un archivo subido. El contenido vive en el almacenamiento persistente
 * ({@code APP_STORAGE_DIR}) bajo {@code storageKey}, generado por el servidor; nunca dentro del
 * artefacto desplegado, así sobrevive a cualquier redeploy.
 */
@Entity
@Table(name = "stored_files")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class StoredFile extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_key", nullable = false, unique = true, length = 300)
    private String storageKey;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "mime_type", nullable = false, length = 50)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    private Integer width;

    private Integer height;

    @Column(name = "owner_type", length = 40)
    private String ownerType;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
