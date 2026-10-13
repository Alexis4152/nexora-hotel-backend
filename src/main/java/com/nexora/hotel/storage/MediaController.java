package com.nexora.hotel.storage;

import com.nexora.hotel.entity.StoredFile;
import com.nexora.hotel.repository.StoredFileRepository;
import com.nexora.hotel.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Sirve las imágenes públicas del hotel (fotos, logos, portada) por {@code /media/<clave>}. Solo entrega
 * archivos registrados y no borrados; el tipo de contenido sale de la BD (no del archivo) y se envía
 * {@code nosniff}. La clave lleva un UUID, por eso se puede cachear de forma permanente.
 */
@RestController
@RequiredArgsConstructor
public class MediaController {

    private final StoredFileRepository repository;
    private final StorageService storage;

    @GetMapping("/media/**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) {
        String key = request.getRequestURI().substring("/media/".length());
        Optional<Path> path = storage.pathFor(key);
        Optional<StoredFile> file = path.isEmpty() ? Optional.empty()
                : TenantContext.callAs(TenantContext.ROOT, () -> repository.findByStorageKeyAndDeletedAtIsNull(key));
        if (file.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.get().getMimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .body(new FileSystemResource(path.get()));
    }
}
