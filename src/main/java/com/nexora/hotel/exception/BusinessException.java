package com.nexora.hotel.exception;

/** Violación de una regla de negocio. El mensaje se muestra tal cual al usuario: debe ser claro y no técnico. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
