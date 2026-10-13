package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.dto.catalog.ReservationDtos.EmailSettingsRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.EmailSettingsResponse;
import com.nexora.hotel.dto.catalog.ReservationDtos.OutboxEntry;
import com.nexora.hotel.dto.catalog.ReservationDtos.TemplateRequest;
import com.nexora.hotel.dto.catalog.ReservationDtos.TemplateResponse;
import com.nexora.hotel.entity.EmailSettings;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.NotificationTemplate;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.notification.MailService;
import com.nexora.hotel.notification.NotificationService;
import com.nexora.hotel.repository.EmailSettingsRepository;
import com.nexora.hotel.repository.NotificationTemplateRepository;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SMTP propio del hotel, plantillas de correo y bitácora de envíos. La contraseña SMTP se cifra (AES-GCM) y nunca
 * se devuelve: la respuesta solo indica si existe.
 */
@Service
@RequiredArgsConstructor
public class EmailSettingsService {

    private final EmailSettingsRepository repository;
    private final NotificationTemplateRepository templateRepository;
    private final MailService mailService;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public EmailSettingsResponse get() {
        return toDto(repository.findFirstBy().orElseGet(() -> EmailSettings.builder().build()));
    }

    @Transactional
    public EmailSettingsResponse update(EmailSettingsRequest r) {
        EmailSettings s = repository.findFirstBy().orElseGet(() -> repository.save(EmailSettings.builder().build()));
        if (r.enabled() && (blank(r.host()) == null || blank(r.fromAddress()) == null)) {
            throw new BusinessException("Para activar el correo indica el servidor y el correo remitente");
        }
        validateRecipients(r.adminRecipients(), "destinatarios de avisos");
        validateRecipients(r.bccRecipients(), "copias ocultas");
        s.setEnabled(r.enabled());
        s.setHost(blank(r.host()));
        s.setPort(r.port());
        s.setUsername(blank(r.username()));
        if (r.password() != null && !r.password().isBlank()) {
            s.setPassword(r.password());
        }
        s.setStarttls(r.starttls());
        s.setFromAddress(lower(r.fromAddress()));
        s.setFromName(blank(r.fromName()));
        s.setReplyTo(lower(r.replyTo()));
        s.setAdminRecipients(normalizeList(r.adminRecipients()));
        s.setBccRecipients(normalizeList(r.bccRecipients()));
        // Nunca se registra la contraseña, solo que cambió.
        auditService.record(AuditEntry.success("EMAIL_SETTINGS_UPDATED", "EmailSettings", s.getId())
                .after(Map.of("enabled", s.isEnabled(), "host", String.valueOf(s.getHost()),
                        "passwordChanged", r.password() != null && !r.password().isBlank()))
                .build());
        return toDto(s);
    }

    /** Envío de prueba inmediato con la configuración guardada (sin pasar por la cola). */
    @Transactional(readOnly = true)
    public MailService.Delivery sendTest(String to) {
        EmailSettings s = repository.findFirstBy().filter(EmailSettings::usable)
                .orElseThrow(() -> new BusinessException("Activa y guarda la configuración de correo antes de probarla"));
        Hotel hotel = hotelDirectory.current();
        String text = "Este es un correo de prueba de " + hotel.getName() + ".\n\nSi lo recibes, la configuración es correcta.";
        return mailService.deliver(new MailService.Smtp(s.getHost(), s.getPort(), s.getUsername(), s.getPassword(), s.isStarttls(),
                        s.getFromAddress(), s.getFromName() == null ? hotel.getName() : s.getFromName(), s.getReplyTo()),
                hotel.getName(), to, null, "Prueba de correo · " + hotel.getName(),
                NotificationService.layout(hotel.getName(), text, null), text);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> templates() {
        Map<String, NotificationTemplate> saved = templateRepository.findAllByOrderByEventCodeAsc().stream()
                .collect(Collectors.toMap(NotificationTemplate::getEventCode, t -> t));
        return NotificationService.DEFAULTS.entrySet().stream().map(e -> {
            NotificationTemplate t = saved.get(e.getKey().name());
            var d = e.getValue();
            return t == null
                    ? new TemplateResponse(e.getKey().name(), d.label(), d.subject(), d.body(), true, d.toGuest(), d.toAdmins(), NotificationService.VARIABLES)
                    : new TemplateResponse(e.getKey().name(), d.label(), t.getSubject(), t.getBody(), t.isEnabled(), t.isSendToGuest(),
                    t.isSendToAdmins(), NotificationService.VARIABLES);
        }).toList();
    }

    @Transactional
    public TemplateResponse updateTemplate(NotificationEvent event, TemplateRequest r) {
        NotificationTemplate t = templateRepository.findByEventCode(event.name())
                .orElseGet(() -> NotificationTemplate.builder().eventCode(event.name()).build());
        t.setSubject(r.subject().trim());
        t.setBody(r.body().trim());
        t.setEnabled(r.enabled());
        t.setSendToGuest(r.sendToGuest());
        t.setSendToAdmins(r.sendToAdmins());
        templateRepository.save(t);
        auditService.record(AuditEntry.success("NOTIFICATION_TEMPLATE_UPDATED", "NotificationTemplate", event.name()).build());
        var d = NotificationService.DEFAULTS.get(event);
        return new TemplateResponse(event.name(), d.label(), t.getSubject(), t.getBody(), t.isEnabled(), t.isSendToGuest(),
                t.isSendToAdmins(), NotificationService.VARIABLES);
    }

    @Transactional
    public void resetTemplate(NotificationEvent event) {
        templateRepository.findByEventCode(event.name()).ifPresent(templateRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<OutboxEntry> recent() {
        return jdbc.query("""
                SELECT id, event_code, recipient, subject, status, attempts, last_error, created_at, sent_at
                FROM notification_outbox WHERE hotel_id = ? ORDER BY id DESC LIMIT 30""",
                (rs, i) -> new OutboxEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()),
                TenantContext.requireHotelId());
    }

    private static EmailSettingsResponse toDto(EmailSettings s) {
        return new EmailSettingsResponse(s.isEnabled(), s.getHost(), s.getPort(), s.getUsername(),
                s.getPassword() != null && !s.getPassword().isBlank(), s.isStarttls(), s.getFromAddress(), s.getFromName(),
                s.getReplyTo(), s.getAdminRecipients(), s.getBccRecipients(), s.usable());
    }

    private static void validateRecipients(String list, String label) {
        if (list == null || list.isBlank()) {
            return;
        }
        boolean invalid = Arrays.stream(list.split("[,;\\s]+")).filter(x -> !x.isBlank())
                .anyMatch(x -> !x.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$"));
        if (invalid) {
            throw new BusinessException("Revisa los correos de " + label + " (sepáralos con coma)");
        }
    }

    private static String normalizeList(String list) {
        if (list == null || list.isBlank()) {
            return null;
        }
        return Arrays.stream(list.split("[,;\\s]+")).filter(x -> !x.isBlank()).map(String::toLowerCase).distinct()
                .collect(Collectors.joining(", "));
    }

    private static String lower(String v) {
        String b = blank(v);
        return b == null ? null : b.toLowerCase();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
