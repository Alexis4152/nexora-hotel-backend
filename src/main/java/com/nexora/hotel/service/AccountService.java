package com.nexora.hotel.service;

import com.nexora.hotel.domain.CancellationEvaluator;
import com.nexora.hotel.dto.catalog.PaymentDtos.Account;
import com.nexora.hotel.dto.catalog.PaymentDtos.ChargeView;
import com.nexora.hotel.dto.catalog.PaymentDtos.GuestView;
import com.nexora.hotel.dto.catalog.PaymentDtos.PaymentView;
import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.entity.Reservation;
import com.nexora.hotel.entity.ReservationCharge;
import com.nexora.hotel.entity.ReservationGuest;
import com.nexora.hotel.enums.CatalogEnums.PenaltyType;
import com.nexora.hotel.enums.PaymentEnums.PaymentKind;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import com.nexora.hotel.enums.ReservationEnums.ReservationStatus;
import com.nexora.hotel.repository.PaymentRepository;
import com.nexora.hotel.repository.ReservationChargeRepository;
import com.nexora.hotel.repository.ReservationGuestRepository;
import com.nexora.hotel.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Cuenta de una reservación (lo que se debe, lo cobrado, cargos, huéspedes registrados). Solo lectura. */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final PaymentRepository payments;
    private final ReservationChargeRepository charges;
    private final ReservationGuestRepository guests;
    private final PaymentSettingsService paymentSettings;
    private final UserNames userNames;

    public Account account(Reservation r) {
        List<Payment> list = payments.findByReservationIdOrderByCreatedAtAscIdAsc(r.getId());
        List<ReservationCharge> chargeList = charges.findByReservationIdOrderByCreatedAtAscIdAsc(r.getId());
        List<Long> users = new ArrayList<>();
        list.forEach(p -> users.add(p.getCreatedBy()));
        chargeList.forEach(c -> users.add(c.getCreatedBy()));
        users.add(r.getCheckedInBy());
        users.add(r.getCheckedOutBy());
        Map<Long, String> names = userNames.of(users);
        boolean sensitive = SecurityUtils.currentPrincipal().map(a -> a.hasPermission("CUSTOMERS:VIEW_SENSITIVE")).orElse(false);

        List<PaymentView> paymentViews = list.stream().map(p -> new PaymentView(p.getId(), p.getKind(), p.getMethod(),
                p.getStatus(), p.getAmount(), p.getReference(), p.getNotes(), p.getRelatedPaymentId(), p.getProvider(),
                p.getCardBrand(), p.getCardLast4(), p.getStatus() == PaymentStatus.PENDING ? p.getCheckoutUrl() : null,
                p.getExpiresAt(), p.getFailureReason(), names.get(p.getCreatedBy()), p.getCreatedAt(), p.getCompletedAt(),
                remaining(p, list))).toList();
        List<ChargeView> chargeViews = chargeList.stream().map(c -> new ChargeView(c.getId(), c.getDescription(),
                c.getQuantity(), c.getUnitPrice(), c.getAmount(), names.get(c.getCreatedBy()), c.getCreatedAt(), c.isVoided(),
                c.getVoidReason(), c.getVoidedAt())).toList();
        List<GuestView> guestViews = guests.findByReservationIdOrderByIdAsc(r.getId()).stream()
                .map(g -> new GuestView(g.getId(), g.getFullName(), g.isMinor(), g.getIdType(), mask(g, sensitive))).toList();

        BigDecimal penalty = r.getStatus() == ReservationStatus.CANCELLED || r.getStatus() == ReservationStatus.NO_SHOW
                ? r.getPenaltyAmount() : BigDecimal.ZERO;
        return new Account(r.getTotal(), penalty, r.getChargesTotal(), r.amountDue(), r.getPaidTotal(), r.balance(),
                r.getDepositRequired(), ReservationService.depositPending(r), paymentViews, chargeViews, guestViews,
                r.getCheckedInAt(), names.get(r.getCheckedInBy()), r.getCheckInNotes(), r.getCheckedOutAt(),
                names.get(r.getCheckedOutBy()), r.getCheckOutNotes(), r.getNoShowAt(),
                r.getStatus() == ReservationStatus.CONFIRMED ? noShowPenalty(r) : null, paymentSettings.settings().onlineUsable());
    }

    /**
     * Lo que aún se puede reembolsar o reversar de un pago: su importe menos los reembolsos y reversos ligados (los
     * pendientes también cuentan, para que dos devoluciones simultáneas no excedan el pago).
     */
    public static BigDecimal remaining(Payment p, List<Payment> all) {
        if (p.getKind() != PaymentKind.PAYMENT || p.getStatus() != PaymentStatus.COMPLETED) {
            return BigDecimal.ZERO;
        }
        BigDecimal used = all.stream()
                .filter(o -> p.getId().equals(o.getRelatedPaymentId()) && o.getKind().isOutflow()
                        && (o.getStatus() == PaymentStatus.COMPLETED || o.getStatus() == PaymentStatus.PENDING))
                .map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return p.getAmount().subtract(used).max(BigDecimal.ZERO);
    }

    /** Penalización por no presentarse según la política ACEPTADA (snapshot). */
    public static BigDecimal noShowPenalty(Reservation r) {
        Map<String, Object> p = r.getPolicySnapshot();
        CancellationEvaluator.Policy policy = new CancellationEvaluator.Policy(0,
                PenaltyType.valueOf(String.valueOf(p.getOrDefault("noShowPenaltyType", "FIRST_NIGHT"))),
                new BigDecimal(String.valueOf(p.getOrDefault("noShowPenaltyValue", "0"))));
        BigDecimal firstNight = new BigDecimal(String.valueOf(p.getOrDefault("firstNight", "0")));
        return CancellationEvaluator.penalty(policy, firstNight, r.getTotal());
    }

    private static String mask(ReservationGuest g, boolean sensitive) {
        String n = g.getIdNumber();
        if (n == null || n.isBlank()) {
            return null;
        }
        return sensitive ? n : "•••• " + n.substring(Math.max(0, n.length() - 4));
    }
}
