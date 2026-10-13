package com.nexora.hotel.notification;

import com.nexora.hotel.config.AppProperties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Envío de correo. Cada hotel puede tener su propio SMTP ({@link Smtp}); si no lo tiene, se usa el de la
 * plataforma ({@code app.mail.*}). Sin ninguno configurado NO se simula el envío: en dev el contenido va al log
 * para poder probar el flujo y el resultado es {@link Outcome#SKIPPED}.
 */
@Slf4j
@Service
public class MailService {

    public enum Outcome { SENT, SKIPPED, FAILED }

    public record Delivery(Outcome outcome, String error) {
    }

    /** SMTP de un hotel (la contraseña ya descifrada, solo en memoria). */
    public record Smtp(String host, int port, String username, String password, boolean starttls, String fromAddress,
                       String fromName, String replyTo) {
    }

    private final AppProperties.Mail platform;
    private final boolean dev;

    public MailService(AppProperties props, Environment environment) {
        this.platform = props.mail();
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** Correo de plataforma (recuperación de contraseña). */
    public boolean send(String to, String subject, String html, String plainTextForDevLog) {
        return deliver(null, null, to, null, subject, html, plainTextForDevLog).outcome() == Outcome.SENT;
    }

    /**
     * @param hotelSmtp SMTP del hotel o null para usar el de la plataforma
     * @param senderName nombre visible del remitente cuando se usa el SMTP de la plataforma
     */
    public Delivery deliver(Smtp hotelSmtp, String senderName, String to, String bcc, String subject, String html, String text) {
        Smtp smtp = hotelSmtp != null ? hotelSmtp : platformSmtp(senderName);
        if (smtp == null) {
            if (dev) {
                log.warn("[DEV] SMTP sin configurar. Correo para {} — {}\n{}", to, subject, text);
            } else {
                log.warn("SMTP sin configurar: no se envió el correo '{}'", subject);
            }
            return new Delivery(Outcome.SKIPPED, "No hay servidor de correo configurado");
        }
        try {
            JavaMailSenderImpl sender = build(smtp);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(smtp.fromAddress(), smtp.fromName(), StandardCharsets.UTF_8.name()));
            helper.setTo(to);
            if (bcc != null && !bcc.isBlank()) {
                helper.setBcc(InternetAddress.parse(bcc));
            }
            if (smtp.replyTo() != null && !smtp.replyTo().isBlank()) {
                helper.setReplyTo(smtp.replyTo());
            }
            helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
            return new Delivery(Outcome.SENT, null);
        } catch (Exception e) {
            // Sin destinatario en el log: es dato personal.
            log.error("Falló el envío del correo '{}': {}", subject, e.getMessage());
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Delivery(Outcome.FAILED, error.length() > 480 ? error.substring(0, 480) : error);
        }
    }

    private Smtp platformSmtp(String senderName) {
        if (!platform.configured()) {
            return null;
        }
        return new Smtp(platform.host(), platform.port(), platform.username(), platform.password(), true,
                platform.from(), senderName == null ? "Nexora Hotel" : senderName, null);
    }

    private static JavaMailSenderImpl build(Smtp cfg) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(cfg.host());
        impl.setPort(cfg.port());
        impl.setUsername(cfg.username());
        impl.setPassword(cfg.password());
        impl.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties p = impl.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(cfg.username() != null && !cfg.username().isBlank()));
        p.put("mail.smtp.starttls.enable", String.valueOf(cfg.starttls()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return impl;
    }
}
