package com.nexora.hotel.service;

import com.nexora.hotel.tenant.TenantContext;
import com.nexora.hotel.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tareas programadas de reservaciones y operación, por hotel (cada uno en su propia transacción y tenant). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationJobs {

    private final TenantRunner tenantRunner;
    private final ReservationService reservationService;
    private final PaymentService paymentService;
    private final OperationsAlertService operationsAlerts;

    /** Cada minuto: libera solicitudes no confirmadas dentro del plazo configurado. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expireOverdue() {
        tenantRunner.forEachActiveHotel("expirar reservaciones", hotel -> {
            int expired = reservationService.expireOverdue();
            if (expired > 0) {
                log.info("{} reservación(es) expirada(s) en {}", expired, hotel.getSlug());
            }
        });
    }

    /** Cada 2 minutos: consulta en la pasarela los pagos en línea pendientes (por si el webhook no llegó). */
    @Scheduled(fixedDelay = 120_000, initialDelay = 45_000)
    public void reconcilePayments() {
        tenantRunner.forEachActiveHotel("conciliar pagos en línea", hotel -> {
            int done = paymentService.reconcilePending();
            if (done > 0) {
                log.debug("{} pago(s) en línea conciliado(s) en {}", done, hotel.getSlug());
            }
        });
    }

    /** Cada 5 minutos: avisos de la operación del día (llegadas, salidas atrasadas, posibles no-shows). */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void operationsAlerts() {
        tenantRunner.forEachActiveHotel("avisos de operación", hotel -> operationsAlerts.check());
    }

    /** Diario: borra los avisos de la campanita con más de 90 días. */
    @Scheduled(cron = "0 15 4 * * *")
    public void purgeNotifications() {
        int deleted = TenantContext.callAs(TenantContext.ROOT, operationsAlerts::purgeOld);
        if (deleted > 0) {
            log.info("{} aviso(s) antiguos de la campanita eliminados", deleted);
        }
    }
}
