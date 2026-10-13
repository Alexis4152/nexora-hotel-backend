package com.nexora.hotel.storage;

import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.entity.StoredFile;
import com.nexora.hotel.repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** URL pública absoluta de un archivo ({@code app.public-url}/media/clave). */
@Component
@RequiredArgsConstructor
public class MediaUrls {

    private final AppProperties props;
    private final StoredFileRepository repository;

    public String of(StoredFile file) {
        return file == null || file.getDeletedAt() != null ? null : base() + "/media/" + file.getStorageKey();
    }

    public String ofId(Long fileId) {
        return fileId == null ? null : repository.findById(fileId).map(this::of).orElse(null);
    }

    private String base() {
        return props.publicUrl().replaceAll("/+$", "");
    }
}
