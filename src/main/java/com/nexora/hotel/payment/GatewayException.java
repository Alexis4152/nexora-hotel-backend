package com.nexora.hotel.payment;

/**
 * Error al hablar con la pasarela. {@code communication} distingue una falla de red o un tiempo agotado (el cargo
 * pudo haberse creado o no: se debe reconciliar después) de un rechazo explícito de la pasarela.
 */
public class GatewayException extends RuntimeException {

    private final boolean communication;
    private final Integer httpStatus;

    public GatewayException(String message, boolean communication, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.communication = communication;
        this.httpStatus = httpStatus;
    }

    public boolean isCommunication() {
        return communication;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
