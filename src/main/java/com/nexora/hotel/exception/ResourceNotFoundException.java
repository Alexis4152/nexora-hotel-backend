package com.nexora.hotel.exception;

/** 404. También se usa para recursos de otro hotel o de otro huésped: nunca se revela que existen. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
