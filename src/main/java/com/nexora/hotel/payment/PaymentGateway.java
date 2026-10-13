package com.nexora.hotel.payment;

import java.math.BigDecimal;

/**
 * Pasarela de pago en línea, desacoplada del resto del sistema. Hoy la implementa {@link OpenpayGateway}; otra
 * pasarela compatible con México solo necesita implementar esta interfaz.
 * <p>
 * Regla de oro: un pago se marca como cobrado únicamente con lo que responde {@link #getCharge} (consulta directa
 * a la pasarela con la llave privada). Ni el regreso del navegador ni el cuerpo de un webhook bastan.
 */
public interface PaymentGateway {

    String provider();

    /** Crea un cargo con página de pago alojada por la pasarela; devuelve la URL a la que se redirige al huésped. */
    Charge createRedirectCharge(Credentials credentials, ChargeRequest request);

    Charge getCharge(Credentials credentials, String chargeId);

    /** Reembolso total o parcial de un cargo completado. */
    Charge refund(Credentials credentials, String chargeId, BigDecimal amount, String description);

    /** Verifica que las credenciales sean válidas (consulta los datos del comercio). */
    void ping(Credentials credentials);

    record Credentials(String merchantId, String privateKey, boolean sandbox) {
        @Override
        public String toString() {
            return "Credentials[merchantId=" + merchantId + ", sandbox=" + sandbox + "]";
        }
    }

    record Customer(String name, String lastName, String email, String phone) {
    }

    record ChargeRequest(String orderId, BigDecimal amount, String currency, String description, Customer customer,
                         String redirectUrl) {
    }

    /** Estado normalizado de un cargo. */
    enum State { PENDING, COMPLETED, FAILED, CANCELLED, REFUNDED }

    record Charge(String id, String orderId, String rawStatus, State state, BigDecimal amount, String currency,
                  String redirectUrl, String errorMessage, String cardBrand, String cardLast4, BigDecimal refundedAmount) {
    }
}
