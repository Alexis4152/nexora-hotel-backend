package com.nexora.hotel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Tipo de habitación (Individual, Doble, Suite...). Configurable por hotel: no hay tipos fijos en el código.
 * Define precio base por noche, capacidad y cargos por huésped adicional sobre {@code includedOccupancy}.
 */
@Entity
@Table(name = "room_types")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class RoomType extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 3000)
    private String description;

    @Column(name = "base_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "max_adults", nullable = false)
    private int maxAdults;

    @Column(name = "max_children", nullable = false)
    private int maxChildren;

    @Column(name = "max_occupancy", nullable = false)
    private int maxOccupancy;

    @Column(name = "included_occupancy", nullable = false)
    private int includedOccupancy;

    @Column(name = "extra_adult_fee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal extraAdultFee = BigDecimal.ZERO;

    @Column(name = "extra_child_fee", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal extraChildFee = BigDecimal.ZERO;

    @Column(name = "bed_count", nullable = false)
    private int bedCount;

    @Column(name = "bed_type", nullable = false, length = 40)
    private String bedType;

    @Column(name = "size_m2", precision = 6, scale = 1)
    private BigDecimal sizeM2;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "room_type_amenities",
            joinColumns = @JoinColumn(name = "room_type_id"),
            inverseJoinColumns = @JoinColumn(name = "amenity_id"))
    @Builder.Default
    private Set<Amenity> amenities = new HashSet<>();
}
