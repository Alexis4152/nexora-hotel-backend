package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Locale;

/** Cliente/huésped del hotel. Puede tener cuenta ({@code userId}) o no (reservó como invitado o en recepción). */
@Entity
@Table(name = "customers")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Customer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 150)
    private String email;

    @Column(name = "phone_country_code", nullable = false, length = 4)
    @Builder.Default
    private String phoneCountryCode = "52";

    @Column(length = 20)
    private String phone;

    @Column(nullable = false, length = 2)
    @Builder.Default
    private String country = "MX";

    @Column(length = 1000)
    private String preferences;

    @Column(length = 2000)
    private String notes;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "privacy_accepted_at")
    private Instant privacyAcceptedAt;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    public String fullName() {
        return firstName + " " + lastName;
    }

    @PrePersist
    @PreUpdate
    void normalize() {
        if (email != null) {
            email = email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        }
    }
}
