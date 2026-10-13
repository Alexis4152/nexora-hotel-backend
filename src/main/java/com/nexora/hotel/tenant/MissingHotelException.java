package com.nexora.hotel.tenant;

import com.nexora.hotel.exception.BusinessException;

/** La operación necesita saber el hotel y la petición no lo trae (falta el enlace /h/&lt;slug&gt;). */
public class MissingHotelException extends BusinessException {

    public MissingHotelException() {
        super("No se identificó el hotel. Entra desde el enlace de tu hotel.");
    }
}
