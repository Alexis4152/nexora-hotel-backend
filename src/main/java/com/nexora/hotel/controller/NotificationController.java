package com.nexora.hotel.controller;

import com.nexora.hotel.dto.ApiResponse;
import com.nexora.hotel.dto.PageResponse;
import com.nexora.hotel.dto.catalog.InboxDtos.NotificationView;
import com.nexora.hotel.dto.catalog.InboxDtos.UnreadCount;
import com.nexora.hotel.security.JwtAuthFilter;
import com.nexora.hotel.service.InboxService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Campanita del panel. Fuera de {@code /api/admin} a propósito: no depende de ningún permiso (cada quien ve solo sus
 * propios avisos, que ya se repartieron según permisos al crearse), pero sí exige ser personal del hotel.
 */
@Tag(name = "Notificaciones del panel")
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("hasAuthority('" + JwtAuthFilter.HOTEL_STAFF + "')")
@RequiredArgsConstructor
public class NotificationController {

    private final InboxService inbox;

    @GetMapping
    public ApiResponse<PageResponse<NotificationView>> list(@RequestParam(defaultValue = "false") boolean unread,
                                                            @RequestParam(required = false) String category,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(inbox.list(unread, category, page, size));
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        return ApiResponse.ok(inbox.unread());
    }

    @PostMapping("/{id}/read")
    public ApiResponse<UnreadCount> markRead(@PathVariable Long id) {
        return ApiResponse.ok(inbox.markRead(id));
    }

    @PostMapping("/read-all")
    public ApiResponse<UnreadCount> markAllRead() {
        return ApiResponse.ok(inbox.markAllRead());
    }

    /** Conexión en vivo (Server-Sent Events): eventos {@code ready}, {@code notification} y {@code unread}. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return inbox.subscribe();
    }
}
