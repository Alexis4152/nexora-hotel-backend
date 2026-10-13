package com.nexora.hotel.entity;

import com.nexora.hotel.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * SMTP propio del hotel. La contraseña se cifra en la columna (AES-GCM) y nunca se devuelve al frontend.
 * Remitente, "responder a" y destinatarios de avisos son conceptos distintos.
 */
@Entity
@Table(name = "email_settings")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class EmailSettings extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(length = 200)
    private String host;

    @Column(nullable = false)
    @Builder.Default
    private int port = 587;

    @Column(length = 200)
    private String username;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "password_enc", length = 600)
    private String password;

    @Column(nullable = false)
    @Builder.Default
    private boolean starttls = true;

    @Column(name = "from_address", length = 150)
    private String fromAddress;

    @Column(name = "from_name", length = 150)
    private String fromName;

    @Column(name = "reply_to", length = 150)
    private String replyTo;

    @Column(name = "admin_recipients", length = 1000)
    private String adminRecipients;

    @Column(name = "bcc_recipients", length = 1000)
    private String bccRecipients;

    public boolean usable() {
        return enabled && host != null && !host.isBlank() && fromAddress != null && !fromAddress.isBlank();
    }
}
