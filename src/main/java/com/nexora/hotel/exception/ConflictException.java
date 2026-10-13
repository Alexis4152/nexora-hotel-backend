package com.nexora.hotel.exception;

/** 409: duplicados, estados incompatibles o recursos ocupados por otra operación. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
