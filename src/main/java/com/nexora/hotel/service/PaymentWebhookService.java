package com.nexora.hotel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexora.hotel.audit.AuditEntry;
import com.nexora.hotel.audit.AuditService;
import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.entity.PaymentSettings;
import com.nexora.hotel.payment.OpenpayGateway;
import com.nexora.hotel.repository.PaymentRepository;
import com.nexora.hotel.repository.PaymentSettingsRepository;
import com.nexora.hotel.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Webhooks de Openpay. Openpay autentica sus notificaciones con HTTP Basic (usuario y contraseña que el hotel registra
 * en su panel): se verifican en tiempo constante. Aun autenticado, el cuerpo del evento NO se usa para cambiar el
 * estado de un pago: solo indica qué cargo revisar, y el estado real se consulta a la pasarela (ver
 * {@link PaymentService#settle}). Los eventos repetidos se ignoran (tabla {@code payment_webhook_events}).
 * Nunca se guarda el cuerpo (puede traer datos enmascarados de tarjeta).
 */
@Slf4j
@Service
public class PaymentWebhookService {

    public enum Result { UNAUTHORIZED, VERIFICATION, DUPLICATE, IGNORED, PROCESSED, RETRY }

    private final PaymentSettingsRepository settingsRepository;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PaymentWebhookService(PaymentSettingsRepository settingsRepository, PaymentRepository payments,
                                 PaymentService paymentService, AuditService auditService, JdbcTemplate jdbc,
                                 PlatformTransactionManager txManager) {
        this.settingsRepository = settingsRepository;
        this.payments = payments;
        this.paymentService = paymentService;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Debe llamarse con el hotel del webhook ya fijado en {@link TenantContext}. */
    public Result handleOpenpay(String authorization, JsonNode event) {
        Optional<PaymentSettings> settings = tx.execute(s -> settingsRepository.findFirstBy());
        if (settings.isEmpty() || !authorized(authorization, settings.get())) {
            log.warn("Webhook de Openpay rechazado para el hotel {}: credenciales inválidas", TenantContext.hotelIdOrNull());
            return Result.UNAUTHORIZED;
        }
        String type = text(event.path("type"), 60);
        if ("verification".equals(type)) {
            String code = text(event.path("verification_code"), 40);
            tx.executeWithoutResult(s -> {
                PaymentSettings ps = settingsRepository.findFirstBy().orElseThrow();
                ps.setWebhookVerification(code);
                auditService.record(AuditEntry.success("PAYMENT_WEBHOOK_VERIFICATION", "PaymentSettings", ps.getId()).build());
            });
            return Result.VERIFICATION;
        }
        JsonNode transaction = event.path("transaction");
        String chargeId = text(transaction.path("id"), 80);
        if (type == null || chargeId == null) {
            return Result.IGNORED;
        }
        String dedup = type + ":" + chargeId + ":" + text(transaction.path("status"), 30);
        long hotelId = TenantContext.requireHotelId();
        Boolean seen = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_webhook_events WHERE hotel_id = ? AND provider = ? AND dedup_key = ?)""",
                Boolean.class, hotelId, OpenpayGateway.PROVIDER, dedup);
        if (Boolean.TRUE.equals(seen)) {
            return Result.DUPLICATE;
        }
        Optional<Payment> payment = tx.execute(s -> payments.findByProviderAndProviderChargeId(OpenpayGateway.PROVIDER, chargeId));
        Result result;
        if (payment.isEmpty()) {
            result = Result.IGNORED;
        } else if (paymentService.settle(payment.get().getId())) {
            result = Result.PROCESSED;
            boolean stillPending = Boolean.TRUE.equals(tx.execute(s -> payments.findById(payment.get().getId())
                    .map(p -> p.getStatus() == com.nexora.hotel.enums.PaymentEnums.PaymentStatus.PENDING).orElse(false)));
            if (stillPending) {
                // La pasarela aún no lo resuelve: no se marca como visto para que un reintento vuelva a verificar.
                return result;
            }
        } else {
            // No se pudo consultar la pasarela: no se marca como visto para que Openpay reintente.
            return Result.RETRY;
        }
        jdbc.update("""
                INSERT INTO payment_webhook_events (hotel_id, provider, event_type, transaction_id, dedup_key, result)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (hotel_id, provider, dedup_key) DO NOTHING""",
                hotelId, OpenpayGateway.PROVIDER, type, chargeId, dedup, result.name());
        return result;
    }

    private static boolean authorized(String header, PaymentSettings s) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6) || s.getWebhookUser() == null || s.getWebhookPassword() == null) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String expected = s.getWebhookUser() + ":" + s.getWebhookPassword();
        return MessageDigest.isEqual(decoded.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node, int max) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
