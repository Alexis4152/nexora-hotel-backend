package com.nexora.hotel.dto.catalog;

import java.time.Instant;
import java.util.Map;

/** Avisos de la campanita del panel. */
public final class InboxDtos {

    private InboxDtos() {
    }

    /**
     * Un aviso. El texto no viaja redactado: la aplicación lo arma con {@code type} y {@code params} en el idioma de
     * quien lo lee. {@code link} es la ruta del panel a la que lleva (sin el prefijo del hotel).
     */
    public record NotificationView(Long id, String type, String category, String severity, Map<String, String> params,
                                   String link, Instant createdAt, boolean read) {
    }

    public record UnreadCount(long unread) {
    }
}
