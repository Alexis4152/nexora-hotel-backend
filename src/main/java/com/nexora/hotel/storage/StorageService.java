package com.nexora.hotel.storage;

import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.entity.StoredFile;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.repository.StoredFileRepository;
import com.nexora.hotel.security.SecurityUtils;
import com.nexora.hotel.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Archivos subidos en disco persistente ({@code APP_STORAGE_DIR}), fuera del artefacto: sobreviven a cualquier
 * redeploy mientras el volumen esté montado. En BD ({@code stored_files}) solo los metadatos.
 * <ul>
 *   <li>Clave generada por el servidor: {@code hotels/<id>/<carpeta>/<uuid>.<ext>}; el nombre del cliente
 *       nunca forma parte de la ruta.</li>
 *   <li>Toda ruta resuelta se verifica dentro de la raíz (path traversal).</li>
 *   <li>Escritura con {@code CREATE_NEW}: jamás sobrescribe.</li>
 *   <li>Si la transacción que registra el archivo se revierte, el archivo físico se borra.</li>
 * </ul>
 */
@Slf4j
@Service
public class StorageService {

    private static final Pattern KEY = Pattern.compile("^hotels/\\d+/[a-z0-9-]{2,30}/[0-9a-f-]{36}\\.(jpg|png|webp)$");
    private static final Pattern FOLDER = Pattern.compile("^[a-z0-9-]{2,30}$");

    private final Path root;
    private final long maxBytes;
    private final StoredFileRepository repository;
    private final Clock clock;

    public StorageService(AppProperties props, StoredFileRepository repository, Clock clock) {
        this.root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        this.maxBytes = props.storage().maxImageBytes();
        this.repository = repository;
        this.clock = clock;
    }

    /** Guarda una imagen del hotel actual. Debe llamarse dentro de una transacción. */
    public StoredFile storeImage(MultipartFile file, String folder, String ownerType, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Selecciona una imagen");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException("La imagen no debe pesar más de " + (maxBytes / (1024 * 1024)) + " MB");
        }
        if (!FOLDER.matcher(folder).matches()) {
            throw new IllegalArgumentException("Carpeta inválida");
        }
        long hotelId = TenantContext.requireHotelId();
        byte[] raw;
        try (InputStream in = file.getInputStream()) {
            raw = in.readNBytes((int) maxBytes + 1);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer la imagen");
        }
        ImageSanitizer.CleanImage clean = ImageSanitizer.sanitize(raw);

        String key = "hotels/" + hotelId + "/" + folder + "/" + UUID.randomUUID() + "." + clean.extension();
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, clean.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            log.error("No se pudo escribir el archivo en el almacenamiento: {}", e.getMessage());
            throw new BusinessException("No se pudo guardar la imagen. Intenta de nuevo.");
        }
        deleteFileIfRolledBack(target);

        return repository.save(StoredFile.builder()
                .storageKey(key)
                .originalName(safeName(file.getOriginalFilename()))
                .mimeType(clean.mimeType())
                .sizeBytes(clean.bytes().length)
                .sha256(sha256(clean.bytes()))
                .width(clean.width())
                .height(clean.height())
                .ownerType(ownerType)
                .ownerId(ownerId)
                .uploadedBy(SecurityUtils.currentPrincipal().map(p -> p.userId()).orElse(null))
                .build());
    }

    /** Borrado lógico del registro; el archivo físico se elimina cuando la transacción confirma. */
    public void delete(StoredFile file) {
        file.setDeletedAt(clock.instant());
        Path path = resolve(file.getStorageKey());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(path);
                }
            });
        }
    }

    /** Ruta física de una clave válida y existente; vacío si no cumple el formato o no existe. */
    public Optional<Path> pathFor(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path path = resolve(key);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Ruta fuera del almacenamiento");
        }
        return path;
    }

    private void deleteFileIfRolledBack(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(target);
                }
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("No se pudo borrar el archivo {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private static String safeName(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return clean.length() > 255 ? clean.substring(clean.length() - 255) : clean;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
