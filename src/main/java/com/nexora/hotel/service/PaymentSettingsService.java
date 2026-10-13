package com.nexora.hotel.service;

import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.config.AppProperties;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentSettingsRequest;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentSettingsResponse;
import com.nexora.hotel.dto.catalog.PaymentDtos.PublicPaymentOptions;
import com.nexora.hotel.dto.catalog.PaymentDtos.WebhookCredentials;
import com.nexora.hotel.entity.PaymentSettings;
import com.nexora.hotel.exception.BusinessException;
import com.nexora.hotel.payment.GatewayException;
import com.nexora.hotel.payment.PaymentGateway;
import com.nexora.hotel.repository.PaymentSettingsRepository;
import com.nexora.hotel.security.SecureTokens;
import com.nexora.hotel.tenant.HotelDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Métodos de cobro y credenciales de la pasarela del hotel. La llave privada y la contraseña del webhook se guardan
 * cifradas y nunca se devuelven; la respuesta solo indica si existen.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettingsService {

    private final PaymentSettingsRepository repository;
    private final PaymentGateway gateway;
    private final HotelDirectory hotelDirectory;
    private final AuditService auditService;
    private final AppProperties props;

    /** Configuración vigente (o los valores por defecto si el hotel aún no la tiene). Solo lectura. */
    public PaymentSettings settings() {
        return repository.findFirstBy().orElseGet(() -> PaymentSettings.builder().build());
    }

    private PaymentSettings settingsForUpdate() {
        return repository.findFirstBy().orElseGet(() -> repository.save(PaymentSettings.builder().build()));
    }

    public PaymentGateway.Credentials credentials(PaymentSettings s) {
        if (!s.gatewayConfigured()) {
            throw new BusinessException("El pago en línea no está configurado en este hotel");
        }
        return new PaymentGateway.Credentials(s.getMerchantId(), s.getPrivateKey(), s.isSandbox());
    }

    @Transactional(readOnly = true)
    public PaymentSettingsResponse get() {
        return toDto(settings());
    }

    @Transactional
    public PaymentSettingsResponse update(PaymentSettingsRequest r) {
        PaymentSettings s = settingsForUpdate();
        Map<String, Object> before = describe(s);
        String merchant = blank(r.merchantId());
        boolean keyChanged = r.privateKey() != null && !r.privateKey().isBlank();
        if (r.onlineEnabled() && (merchant == null || (!keyChanged && (s.getPrivateKey() == null || s.getPrivateKey().isBlank())))) {
            throw new BusinessException("Para activar el pago en línea captura el ID de comercio y la llave privada de Openpay");
        }
        if (!r.cashEnabled() && !r.cardEnabled() && !r.transferEnabled() && !r.onlineEnabled()) {
            throw new BusinessException("Deja al menos un método de pago activo");
        }
        if (r.onlineEnabled() && !r.sandbox() && !props.publicUrl().startsWith("https://")) {
            throw new BusinessException("Para cobrar en producción la API debe publicarse con https://");
        }
        s.setCashEnabled(r.cashEnabled());
        s.setCardEnabled(r.cardEnabled());
        s.setTransferEnabled(r.transferEnabled());
        s.setOnlineEnabled(r.onlineEnabled());
        s.setSandbox(r.sandbox());
        s.setMerchantId(merchant);
        if (keyChanged) {
            s.setPrivateKey(r.privateKey().trim());
        }
        s.setPublicKey(blank(r.publicKey()));
        s.setTransferInstructions(blank(r.transferInstructions()));
        if (s.getWebhookUser() == null) {
            s.setWebhookUser("nexora-" + hotelDirectory.current().getSlug());
            s.setWebhookPassword(SecureTokens.randomToken());
        }
        Map<String, Object> after = describe(s);
        after.put("privateKeyChanged", keyChanged);
        // Nunca se registran llaves: solo que cambiaron.
        auditService.record(AuditEntry.success("PAYMENT_SETTINGS_UPDATED", "PaymentSettings", s.getId())
                .before(before).after(after).build());
        return toDto(s);
    }

    /** Genera usuario y contraseña nuevos para el webhook. La contraseña se muestra solo en esta respuesta. */
    @Transactional
    public WebhookCredentials regenerateWebhook() {
        PaymentSettings s = settingsForUpdate();
        String password = SecureTokens.randomToken();
        s.setWebhookUser("nexora-" + hotelDirectory.current().getSlug());
        s.setWebhookPassword(password);
        s.setWebhookVerification(null);
        auditService.record(AuditEntry.success("PAYMENT_WEBHOOK_REGENERATED", "PaymentSettings", s.getId()).build());
        return new WebhookCredentials(webhookUrl(), s.getWebhookUser(), password);
    }

    /** Consulta el comercio en la pasarela con las credenciales guardadas (queda en auditoría). */
    @Transactional
    public String test() {
        PaymentSettings s = settings();
        try {
            gateway.ping(credentials(s));
        } catch (GatewayException e) {
            throw new BusinessException(e.getMessage());
        }
        auditService.record(AuditEntry.success("PAYMENT_GATEWAY_TESTED", "PaymentSettings", s.getId()).build());
        return s.isSandbox() ? "Conexión correcta con Openpay (modo pruebas)" : "Conexión correcta con Openpay (producción)";
    }

    @Transactional(readOnly = true)
    public PublicPaymentOptions publicOptions() {
        PaymentSettings s = settings();
        return new PublicPaymentOptions(s.isCashEnabled(), s.isCardEnabled(), s.isTransferEnabled(), s.onlineUsable(),
                s.isSandbox(), s.isTransferEnabled() ? s.getTransferInstructions() : null);
    }

    public String webhookUrl() {
        return props.publicUrl().replaceAll("/+$", "") + "/api/public/webhooks/openpay/" + hotelDirectory.current().getSlug();
    }

    private PaymentSettingsResponse toDto(PaymentSettings s) {
        return new PaymentSettingsResponse(s.isCashEnabled(), s.isCardEnabled(), s.isTransferEnabled(), s.isOnlineEnabled(),
                s.isSandbox(), s.getProvider(), s.getMerchantId(), s.getPrivateKey() != null && !s.getPrivateKey().isBlank(),
                s.getPublicKey(), webhookUrl(), s.getWebhookUser(), s.getWebhookPassword() != null, s.getWebhookVerification(),
                s.getTransferInstructions(), s.onlineUsable());
    }

    private static Map<String, Object> describe(PaymentSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cash", s.isCashEnabled());
        m.put("card", s.isCardEnabled());
        m.put("transfer", s.isTransferEnabled());
        m.put("online", s.isOnlineEnabled());
        m.put("sandbox", s.isSandbox());
        m.put("merchantId", String.valueOf(s.getMerchantId()));
        return m;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
