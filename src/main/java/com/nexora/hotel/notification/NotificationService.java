package com.nexora.hotel.notification;

import com.nexora.hotel.entity.EmailSettings;
import com.nexora.hotel.entity.Hotel;
import com.nexora.hotel.entity.NotificationTemplate;
import com.nexora.hotel.enums.ReservationEnums.NotificationEvent;
import com.nexora.hotel.repository.EmailSettingsRepository;
import com.nexora.hotel.repository.NotificationTemplateRepository;
import com.nexora.hotel.tenant.HotelDirectory;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encola correos en {@code notification_outbox} dentro de la transacción de la operación que los provoca: si la
 * operación se revierte, el correo nunca sale; si se reintenta, {@code dedup_key} evita el duplicado. El envío
 * real lo hace {@link OutboxProcessor}.
 * <p>
 * Plantillas: texto con variables {@code {{nombre}}}. Se sustituyen y luego se escapa TODO el contenido, así que
 * ni un nombre de huésped ni la plantilla misma pueden inyectar HTML en el correo.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    public record Template(String subject, String body, boolean toGuest, boolean toAdmins, String label) {
    }

    public static final List<String> VARIABLES = List.of("guestName", "folio", "hotelName", "checkIn", "checkOut",
            "nights", "guests", "roomType", "total", "status", "manageUrl", "reason", "penalty", "hotelPhone",
            "amount", "method", "paid", "balance", "payUrl", "expires");

    public static final Map<NotificationEvent, Template> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(NotificationEvent.RESERVATION_CREATED, new Template(
                "Recibimos tu solicitud {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Recibimos tu solicitud de reservación en {{hotelName}}.

                Folio: {{folio}}
                Llegada: {{checkIn}} · Salida: {{checkOut}} ({{nights}} noches)
                Habitación: {{roomType}} · Huéspedes: {{guests}}
                Total estimado: {{total}}

                Estado: {{status}}. Te avisaremos en cuanto el hotel la confirme.
                Puedes consultarla o cancelarla en el enlace de abajo.""", true, true, "Reservación creada"));
        DEFAULTS.put(NotificationEvent.RESERVATION_CONFIRMED, new Template(
                "Tu reservación {{folio}} está confirmada · {{hotelName}}",
                """
                Hola {{guestName}}:

                ¡Tu reservación está confirmada! Te esperamos.

                Folio: {{folio}}
                Llegada: {{checkIn}} · Salida: {{checkOut}} ({{nights}} noches)
                Habitación: {{roomType}} · Huéspedes: {{guests}}
                Total: {{total}}

                Si necesitas algo antes de tu llegada, llámanos al {{hotelPhone}}.""", true, false, "Reservación confirmada"));
        DEFAULTS.put(NotificationEvent.RESERVATION_CANCELLED, new Template(
                "Reservación {{folio}} cancelada · {{hotelName}}",
                """
                Hola {{guestName}}:

                La reservación {{folio}} ({{checkIn}} a {{checkOut}}) fue cancelada.
                Motivo: {{reason}}
                Penalización aplicada: {{penalty}}

                Esperamos recibirte en otra ocasión.""", true, true, "Reservación cancelada"));
        DEFAULTS.put(NotificationEvent.RESERVATION_CHANGED, new Template(
                "Cambios en tu reservación {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Actualizamos tu reservación {{folio}}.

                Llegada: {{checkIn}} · Salida: {{checkOut}} ({{nights}} noches)
                Habitación: {{roomType}} · Huéspedes: {{guests}}
                Nuevo total: {{total}}""", true, false, "Reservación modificada"));
        DEFAULTS.put(NotificationEvent.RESERVATION_ACCESS_LINK, new Template(
                "Acceso a tu reservación {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Solicitaste acceder a tu reservación {{folio}}. Usa el enlace de abajo; vence en 7 días.
                Si no fuiste tú, ignora este correo.""", true, false, "Enlace de acceso"));
        DEFAULTS.put(NotificationEvent.PAYMENT_LINK, new Template(
                "Tu enlace de pago · {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Puedes pagar {{amount}} de tu reservación {{folio}} ({{checkIn}} a {{checkOut}}) de forma segura con el botón de abajo.
                El pago se procesa en la página de nuestra pasarela; nunca te pediremos datos de tarjeta por correo.

                El enlace vence el {{expires}}.""", true, false, "Enlace de pago"));
        DEFAULTS.put(NotificationEvent.PAYMENT_RECEIVED, new Template(
                "Recibimos tu pago · {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Registramos un pago de {{amount}} ({{method}}) para tu reservación {{folio}}.

                Pagado a la fecha: {{paid}}
                Saldo pendiente: {{balance}}""", true, true, "Pago recibido"));
        DEFAULTS.put(NotificationEvent.REFUND_ISSUED, new Template(
                "Reembolso de {{amount}} · {{folio}} · {{hotelName}}",
                """
                Hola {{guestName}}:

                Registramos un reembolso de {{amount}} ({{method}}) para tu reservación {{folio}}.
                Motivo: {{reason}}

                Si se hizo a tu tarjeta, puede tardar algunos días en reflejarse según tu banco.""", true, true, "Reembolso emitido"));
        DEFAULTS.put(NotificationEvent.STAY_COMPLETED, new Template(
                "Gracias por tu estancia · {{hotelName}}",
                """
                Hola {{guestName}}:

                Gracias por hospedarte con nosotros. Registramos tu salida de la reservación {{folio}}.

                Total pagado: {{paid}}

                Esperamos recibirte pronto de nuevo.""", true, false, "Estancia finalizada"));
        DEFAULTS.put(NotificationEvent.ACCOUNT_VERIFY, new Template(
                "Confirma tu cuenta · {{hotelName}}",
                """
                Hola {{guestName}}:

                Gracias por crear tu cuenta en {{hotelName}}. Confirma tu correo con el botón de abajo para empezar a usarla.
                El enlace vence en 24 horas. Si no fuiste tú, ignora este correo.""", true, false, "Confirmación de cuenta"));
        DEFAULTS.put(NotificationEvent.DATA_REQUEST_RECEIVED, new Template(
                "Solicitud de datos personales · {{hotelName}}",
                """
                Se recibió una solicitud de {{reason}} de datos personales de {{guestName}}.

                Atiéndela desde el panel: Clientes → Solicitudes de datos. La ley establece plazos de respuesta.""", false, true,
                "Solicitud de datos personales"));
    }

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");

    private final NotificationTemplateRepository templateRepository;
    private final EmailSettingsRepository emailSettingsRepository;
    private final HotelDirectory hotelDirectory;
    private final JdbcTemplate jdbc;

    public Template template(NotificationEvent event) {
        return templateRepository.findByEventCode(event.name())
                .map(t -> new Template(t.getSubject(), t.getBody(), t.isSendToGuest(), t.isSendToAdmins(), DEFAULTS.get(event).label()))
                .orElse(DEFAULTS.get(event));
    }

    private boolean enabled(NotificationEvent event) {
        return templateRepository.findByEventCode(event.name()).map(NotificationTemplate::isEnabled).orElse(true);
    }

    /**
     * Encola el evento para el huésped y/o los destinatarios administrativos según la plantilla.
     * @param dedupKey identidad del evento (ej. "RESERVATION_CONFIRMED:15"); repetirlo no duplica el correo
     */
    public void enqueue(NotificationEvent event, String dedupKey, String guestEmail, Map<String, String> vars,
                        String relatedType, Long relatedId) {
        if (!enabled(event)) {
            return;
        }
        long hotelId = TenantContext.requireHotelId();
        Hotel hotel = hotelDirectory.current();
        Template t = template(event);
        Set<String> recipients = new LinkedHashSet<>();
        if (t.toGuest() && guestEmail != null && !guestEmail.isBlank()) {
            recipients.add(guestEmail.trim().toLowerCase());
        }
        if (t.toAdmins()) {
            emailSettingsRepository.findFirstBy().map(EmailSettings::getAdminRecipients).ifPresent(list ->
                    Arrays.stream(list.split("[,;\\s]+")).map(String::trim).filter(s -> s.contains("@"))
                            .map(String::toLowerCase).forEach(recipients::add));
        }
        Map<String, String> all = new LinkedHashMap<>(vars);
        all.putIfAbsent("hotelName", hotel.getName());
        String subject = substitute(t.subject(), all);
        String text = substitute(t.body(), all);
        String html = all.containsKey("payUrl") ? layout(hotel.getName(), text, all.get("payUrl"), "Pagar ahora")
                : all.containsKey("verifyUrl") ? layout(hotel.getName(), text, all.get("verifyUrl"), "Confirmar mi cuenta")
                : layout(hotel.getName(), text, all.get("manageUrl"));
        for (String to : recipients) {
            jdbc.update("""
                    INSERT INTO notification_outbox (hotel_id, event_code, dedup_key, recipient, subject, body_html, body_text,
                                                     related_type, related_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (hotel_id, dedup_key, recipient) DO NOTHING""",
                    hotelId, event.name(), dedupKey, to, truncate(subject, 200), html, text, relatedType, relatedId);
        }
    }

    public static String substitute(String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Envuelve el texto (ya sustituido) escapándolo por completo. */
    public static String layout(String hotelName, String text, String actionUrl) {
        return layout(hotelName, text, actionUrl, "Ver mi reservación");
    }

    public static String layout(String hotelName, String text, String actionUrl, String actionLabel) {
        List<String> paragraphs = new ArrayList<>();
        for (String p : text.split("\\n\\s*\\n")) {
            paragraphs.add("<p style=\"margin:0 0 14px;line-height:1.6\">" + HtmlUtils.htmlEscape(p.trim()).replace("\n", "<br>") + "</p>");
        }
        String button = actionUrl == null || !actionUrl.startsWith("http") ? "" : """
                <p style="margin:22px 0"><a href="%s" style="display:inline-block;background:#0f766e;color:#ffffff;padding:12px 22px;border-radius:12px;text-decoration:none;font-weight:600">%s</a></p>"""
                .formatted(HtmlUtils.htmlEscape(actionUrl), HtmlUtils.htmlEscape(actionLabel));
        return """
                <div style="background:#f1f5f9;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#0f172a">
                  <div style="max-width:560px;margin:auto;background:#ffffff;border-radius:18px;overflow:hidden">
                    <div style="background:#0a0f1c;color:#ffffff;padding:22px 28px;font-size:20px;font-weight:bold">%s</div>
                    <div style="padding:26px 28px;font-size:15px">%s%s</div>
                    <div style="padding:14px 28px;background:#f8fafc;color:#64748b;font-size:12px">Este correo se generó automáticamente. Operado con Nexora Hotel.</div>
                  </div>
                </div>""".formatted(HtmlUtils.htmlEscape(hotelName), String.join("", paragraphs), button);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
