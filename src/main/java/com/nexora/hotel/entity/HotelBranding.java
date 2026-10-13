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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Identidad pública del hotel: datos de contacto, imágenes y tema. El tema es un mapa de colores
 * {@code #RRGGBB} validados (ver {@code ThemeRequest}); nunca CSS libre.
 */
@Entity
@Table(name = "hotel_branding")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class HotelBranding extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 160)
    private String tagline;

    @Column(length = 2000)
    private String description;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(length = 13)
    private String rfc;

    @Column(length = 200)
    private String street;

    @Column(length = 120)
    private String neighborhood;

    @Column(length = 120)
    private String city;

    @Column(length = 120)
    private String state;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(nullable = false, length = 2)
    @Builder.Default
    private String country = "MX";

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(length = 20)
    private String phone;

    @Column(length = 20)
    private String whatsapp;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(length = 255)
    private String website;

    @Column(length = 255)
    private String facebook;

    @Column(length = 255)
    private String instagram;

    @Column(length = 255)
    private String tiktok;

    @Column(name = "logo_file_id")
    private Long logoFileId;

    @Column(name = "logo_alt_file_id")
    private Long logoAltFileId;

    @Column(name = "favicon_file_id")
    private Long faviconFileId;

    @Column(name = "hero_file_id")
    private Long heroFileId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> theme = new HashMap<>();
}
