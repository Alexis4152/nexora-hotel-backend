package com.nexora.hotel.entity;

import com.nexora.hotel.enums.PaymentEnums.IdType;
import com.nexora.hotel.security.EncryptedStringConverter;
import com.nexora.hotel.tenant.TenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** Huésped registrado en el check-in. El número de identificación se cifra y solo se muestra con permiso sensible. */
@Entity
@Table(name = "reservation_guests")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class ReservationGuest extends TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "full_name", nullable = false, length = 201)
    private String fullName;

    @Column(nullable = false)
    private boolean minor;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type", length = 20)
    private IdType idType;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "id_number_enc", length = 600)
    private String idNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
