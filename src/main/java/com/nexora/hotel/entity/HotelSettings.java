package com.nexora.hotel.entity;

import jakarta.persistence.Column;
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

import java.time.LocalTime;

/** Parámetros operativos del hotel (horarios, reglas de reservación). Una fila por hotel. */
@Entity
@Table(name = "hotel_settings")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class HotelSettings extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "check_in_time", nullable = false)
    @Builder.Default
    private LocalTime checkInTime = LocalTime.of(15, 0);

    @Column(name = "check_out_time", nullable = false)
    @Builder.Default
    private LocalTime checkOutTime = LocalTime.of(12, 0);

    @Column(name = "reception_opens", nullable = false)
    @Builder.Default
    private LocalTime receptionOpens = LocalTime.MIDNIGHT;

    @Column(name = "reception_closes", nullable = false)
    @Builder.Default
    private LocalTime receptionCloses = LocalTime.of(23, 59);

    @Column(name = "reception_24h", nullable = false)
    @Builder.Default
    private boolean reception24h = true;

    @Column(name = "min_booking_lead_hours", nullable = false)
    @Builder.Default
    private int minBookingLeadHours = 0;

    @Column(name = "max_booking_lead_days", nullable = false)
    @Builder.Default
    private int maxBookingLeadDays = 365;

    @Column(name = "min_nights", nullable = false)
    @Builder.Default
    private int minNights = 1;

    @Column(name = "max_nights", nullable = false)
    @Builder.Default
    private int maxNights = 30;

    @Column(name = "max_rooms_per_booking", nullable = false)
    @Builder.Default
    private int maxRoomsPerBooking = 5;

    @Column(name = "hold_minutes", nullable = false)
    @Builder.Default
    private int holdMinutes = 15;

    @Column(name = "allow_same_day_booking", nullable = false)
    @Builder.Default
    private boolean allowSameDayBooking = true;

    /** Horas que una solicitud del portal puede esperar confirmación antes de liberarse sola. */
    @Column(name = "pending_confirmation_hours", nullable = false)
    @Builder.Default
    private int pendingConfirmationHours = 24;
}
