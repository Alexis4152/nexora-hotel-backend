package com.nexora.hotel.notification;

import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Envía la cola de correos. {@code FOR UPDATE SKIP LOCKED}: si algún día corren varias instancias, nunca dos
 * envían el mismo correo. Reintentos con espera creciente (1, 2, 4, 8 min); tras 5 intentos queda FAILED.
 * Cada correo usa el SMTP de su hotel, o el de la plataforma si el hotel no tiene.
 */
@Slf4j
@Component
public class OutboxProcessor {

    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final com.nexora.hotel.security.CredentialEncryptionService encryption;
    private final HotelDirectory hotelDirectory;
    private final TransactionTemplate tx;

    public OutboxProcessor(JdbcTemplate jdbc, MailService mailService, com.nexora.hotel.security.CredentialEncryptionService encryption,
                           HotelDirectory hotelDirectory, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mailService = mailService;
        this.encryption = encryption;
        this.hotelDirectory = hotelDirectory;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 15_000)
    public void run() {
        try {
            processBatch();
        } catch (RuntimeException e) {
            log.error("Falló el procesamiento de la cola de correos", e);
        }
    }

    /** Procesa un lote; devuelve cuántos correos se intentaron (útil en pruebas). */
    public int processBatch() {
        Integer processed = TenantContext.callAs(TenantContext.ROOT, () -> tx.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, hotel_id, recipient, subject, body_html, body_text, attempts FROM notification_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED""", BATCH);
            for (Map<String, Object> row : rows) {
                send(row);
            }
            return rows.size();
        }));
        return processed == null ? 0 : processed;
    }

    private void send(Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        long hotelId = ((Number) row.get("hotel_id")).longValue();
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        Hotel hotel = hotelDirectory.get(hotelId);
        // Por JDBC con hotel_id explícito: la transacción está en ROOT y un repositorio filtrado no aplicaría aquí.
        Map<String, Object> cfg = jdbc.queryForList("SELECT * FROM email_settings WHERE hotel_id = ?", hotelId)
                .stream().findFirst().orElse(null);
        boolean usable = cfg != null && Boolean.TRUE.equals(cfg.get("enabled")) && notBlank(cfg.get("host")) && notBlank(cfg.get("from_address"));
        MailService.Smtp smtp = usable
                ? new MailService.Smtp((String) cfg.get("host"), ((Number) cfg.get("port")).intValue(), (String) cfg.get("username"),
                cfg.get("password_enc") == null ? null : encryption.decrypt((String) cfg.get("password_enc")),
                Boolean.TRUE.equals(cfg.get("starttls")), (String) cfg.get("from_address"),
                cfg.get("from_name") == null ? hotel.getName() : (String) cfg.get("from_name"), (String) cfg.get("reply_to"))
                : null;
        String bcc = cfg == null ? null : (String) cfg.get("bcc_recipients");
        MailService.Delivery d = mailService.deliver(smtp, hotel == null ? null : hotel.getName(), (String) row.get("recipient"),
                bcc, (String) row.get("subject"), (String) row.get("body_html"), (String) row.get("body_text"));
        switch (d.outcome()) {
            case SENT -> jdbc.update("UPDATE notification_outbox SET status='SENT', attempts=?, sent_at=now(), last_error=NULL WHERE id=?", attempts, id);
            case SKIPPED -> jdbc.update("UPDATE notification_outbox SET status='SKIPPED', attempts=?, last_error=? WHERE id=?", attempts, d.error(), id);
            case FAILED -> {
                boolean giveUp = attempts >= MAX_ATTEMPTS;
                jdbc.update("""
                        UPDATE notification_outbox SET status = ?, attempts = ?, last_error = ?,
                               next_attempt_at = now() + make_interval(mins => ?) WHERE id = ?""",
                        giveUp ? "FAILED" : "PENDING", attempts, d.error(), 1 << (attempts - 1), id);
            }
        }
    }
    private static boolean notBlank(Object v) {
        return v instanceof String s && !s.isBlank();
    }
}
