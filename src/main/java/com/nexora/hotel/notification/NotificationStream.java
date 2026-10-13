package com.nexora.hotel.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conexiones en vivo (Server-Sent Events) del panel: cada pestaña abierta mantiene una y por ahí llegan los avisos
 * al instante, sin que el navegador pregunte cada tantos segundos. Solo viajan el tipo y los valores del aviso: la
 * aplicación redacta el texto en el idioma de quien lo lee.
 * <p>
 * Vive en memoria: igual que el límite de solicitudes, supone UNA instancia del backend (docs/05). La conexión se
 * cierra sola cada {@link #LIFETIME} y la aplicación se reconecta con su token vigente; así una sesión cerrada o
 * una cuenta desactivada deja de recibir avisos a más tardar en ese plazo. Al reconectar, la aplicación vuelve a
 * pedir el contador, de modo que nada se pierde mientras estuvo desconectada.
 * <p>
 * Los envíos salen de un hilo propio: una conexión lenta nunca retrasa la operación que generó el aviso.
 */
@Slf4j
@Component
public class NotificationStream {

    static final Duration LIFETIME = Duration.ofMinutes(10);
    /** Pestañas simultáneas por usuario; al pasar el límite se cierra la más antigua. */
    static final int MAX_PER_USER = 6;

    private record Subscriber(SseEmitter emitter) {
    }

    private final Map<Long, List<Subscriber>> byUser = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-notifications");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper objectMapper;

    public NotificationStream(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe(long userId, long unreadCount) {
        SseEmitter emitter = new SseEmitter(LIFETIME.toMillis());
        Subscriber subscriber = new Subscriber(emitter);
        List<Subscriber> list = byUser.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>());
        list.add(subscriber);
        while (list.size() > MAX_PER_USER) {
            Subscriber oldest = list.remove(0);
            oldest.emitter().complete();
        }
        Runnable remove = () -> remove(userId, subscriber);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        // Primer evento: confirma la conexión (algunos proxies no la entregan hasta ver datos) y sincroniza el contador.
        send(userId, subscriber, "ready", Map.of("unread", unreadCount));
        return emitter;
    }

    /** Empuja un evento a todas las pestañas abiertas del usuario. */
    public void publish(long userId, String event, Object data) {
        List<Subscriber> list = byUser.get(userId);
        if (list == null || list.isEmpty()) {
            return;
        }
        sender.execute(() -> list.forEach(s -> send(userId, s, event, data)));
    }

    public boolean isConnected(long userId) {
        List<Subscriber> list = byUser.get(userId);
        return list != null && !list.isEmpty();
    }

    /**
     * Latido: un comentario SSE cada 25 s mantiene viva la conexión a través de proxies que cortan las inactivas
     * (Nginx/Traefik suelen hacerlo a los 60 s) y descubre las pestañas que ya se cerraron.
     */
    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    public void heartbeat() {
        byUser.forEach((userId, list) -> sender.execute(() -> list.forEach(s -> {
            try {
                s.emitter().send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                remove(userId, s);
            }
        })));
    }

    private void send(long userId, Subscriber s, String event, Object data) {
        try {
            s.emitter().send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data), MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            // La pestaña se cerró o la conexión se cortó: se descarta; al volver se reconecta sola.
            remove(userId, s);
        }
    }

    private void remove(long userId, Subscriber subscriber) {
        byUser.computeIfPresent(userId, (id, list) -> {
            list.remove(subscriber);
            return list.isEmpty() ? null : list;
        });
    }

    @PreDestroy
    void shutdown() {
        byUser.values().forEach(list -> list.forEach(s -> {
            try {
                s.emitter().complete();
            } catch (RuntimeException e) {
                log.debug("Conexión SSE ya cerrada: {}", e.getMessage());
            }
        }));
        byUser.clear();
        sender.shutdownNow();
    }
}
