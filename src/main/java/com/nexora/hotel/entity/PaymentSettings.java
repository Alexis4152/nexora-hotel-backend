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
 * Métodos de cobro del hotel y credenciales de su pasarela (Openpay). La llave privada y la contraseña del webhook
 * se cifran en la columna (AES-GCM) y nunca se devuelven al frontend.
 */
@Entity
@Table(name = "payment_settings")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class PaymentSettings extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cash_enabled", nullable = false)
    @Builder.Default
    private boolean cashEnabled = true;

    @Column(name = "card_enabled", nullable = false)
    @Builder.Default
    private boolean cardEnabled = true;

    @Column(name = "transfer_enabled", nullable = false)
    @Builder.Default
    private boolean transferEnabled = true;

    @Column(name = "online_enabled", nullable = false)
    @Builder.Default
    private boolean onlineEnabled = false;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String provider = "OPENPAY";

    @Column(nullable = false)
    @Builder.Default
    private boolean sandbox = true;

    @Column(name = "merchant_id", length = 40)
    private String merchantId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "private_key_enc", length = 600)
    private String privateKey;

    @Column(name = "public_key", length = 100)
    private String publicKey;

    @Column(name = "webhook_user", length = 60)
    private String webhookUser;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "webhook_password_enc", length = 600)
    private String webhookPassword;

    @Column(name = "webhook_verification", length = 40)
    private String webhookVerification;

    @Column(name = "transfer_instructions", length = 1000)
    private String transferInstructions;

    /** Hay credenciales completas para hablar con la pasarela. */
    public boolean gatewayConfigured() {
        return merchantId != null && !merchantId.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    /** El cobro en línea está activo y además se puede usar. */
    public boolean onlineUsable() {
        return onlineEnabled && gatewayConfigured();
    }
}
