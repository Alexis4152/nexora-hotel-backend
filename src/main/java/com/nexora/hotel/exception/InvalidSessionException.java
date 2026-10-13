package com.nexora.hotel.exception;

/** 401: refresh token ausente, vencido, revocado o reutilizado. El frontend manda al login. */
public class InvalidSessionException extends RuntimeException {

    public InvalidSessionException(String message) {
        super(message);
    }
}
