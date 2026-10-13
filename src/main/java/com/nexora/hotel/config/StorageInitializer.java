package com.nexora.hotel.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifica al arrancar que el directorio de archivos subidos exista y se pueda escribir. Falla de
 * inmediato en vez de descubrirlo cuando un administrador intente subir el logo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageInitializer {

    private final AppProperties props;

    @PostConstruct
    void init() {
        Path root = Path.of(props.storage().dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el directorio de almacenamiento " + root, e);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("El directorio de almacenamiento no tiene permisos de escritura: " + root);
        }
        log.info("Almacenamiento de archivos en {}", root);
    }
}
