package com.nexora.hotel.entity;

import com.nexora.hotel.enums.CatalogEnums.RoomStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Habitación física. {@code operationalStatus} dice cómo está HOY (limpia, ocupada, en mantenimiento); la
 * disponibilidad para fechas futuras sale de {@code room_occupancies}, no de este campo.
 * {@code @Version}: dos personas editándola a la vez no se pisan.
 */
@Entity
@Table(name = "rooms")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class Room extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false)
    private RoomType roomType;

    @Column(nullable = false, length = 10)
    private String number;

    @Column(length = 100)
    private String name;

    @Column(length = 10)
    private String floor;

    @Column(length = 2000)
    private String description;

    @Column(name = "internal_notes", length = 2000)
    private String internalNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20)
    @Builder.Default
    private RoomStatus operationalStatus = RoomStatus.AVAILABLE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    private long version;
}
