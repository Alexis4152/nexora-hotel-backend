package com.nexora.hotel.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cifra/descifra de forma transparente los campos anotados con {@code @Convert(converter = ...)}: ningún punto
 * de guardado puede "olvidar" cifrar (patrón de DemoPV).
 */
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final CredentialEncryptionService encryption;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null || attribute.isBlank() ? null : encryption.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null || dbData.isBlank() ? null : encryption.decrypt(dbData);
    }
}
