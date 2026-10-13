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

/**
 * Plantilla editable de un correo. El cuerpo es TEXTO con variables {@code {{folio}}}: al enviarse se escapa todo
 * y se envuelve en el diseño del hotel, así ni el administrador ni un huésped pueden inyectar HTML.
 */
@Entity
@Table(name = "notification_templates")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
public class NotificationTemplate extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_code", nullable = false, length = 40)
    private String eventCode;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "send_to_guest", nullable = false)
    @Builder.Default
    private boolean sendToGuest = true;

    @Column(name = "send_to_admins", nullable = false)
    @Builder.Default
    private boolean sendToAdmins = false;
}
