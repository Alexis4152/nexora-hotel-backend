package com.nexora.hotel.repository;

import com.nexora.hotel.entity.Payment;
import com.nexora.hotel.enums.PaymentEnums.PaymentMethod;
import com.nexora.hotel.enums.PaymentEnums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    List<Payment> findByReservationIdOrderByCreatedAtAscIdAsc(Long reservationId);

    List<Payment> findByRelatedPaymentId(Long relatedPaymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByPublicRef(String publicRef);

    Optional<Payment> findByProviderAndProviderChargeId(String provider, String providerChargeId);

    List<Payment> findByMethodAndStatusAndCreatedAtBefore(PaymentMethod method, PaymentStatus status, Instant before);

    List<Payment> findByReservationIdAndMethodAndStatus(Long reservationId, PaymentMethod method, PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(Long id);
}
