-- =====================================================================================
-- Nexora Hotel — V6: clientes, reservaciones, notificaciones (outbox) y correo por hotel.
-- =====================================================================================

-- Solicitudes del portal sin confirmar: se liberan solas pasado este tiempo.
ALTER TABLE hotel_settings ADD COLUMN pending_confirmation_hours INT NOT NULL DEFAULT 24;
ALTER TABLE hotel_settings ADD CONSTRAINT ck_hotel_settings_pending CHECK (pending_confirmation_hours BETWEEN 1 AND 168);

-- ── Clientes ──────────────────────────────────────────────────────────────────────
CREATE TABLE customers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id              BIGINT        NOT NULL REFERENCES hotels(id),
    user_id               BIGINT        REFERENCES users(id),
    first_name            VARCHAR(100)  NOT NULL,
    last_name             VARCHAR(100)  NOT NULL,
    email                 VARCHAR(150),
    phone_country_code    VARCHAR(4)    NOT NULL DEFAULT '52',
    phone                 VARCHAR(20),
    country               VARCHAR(2)    NOT NULL DEFAULT 'MX',
    preferences           VARCHAR(1000),
    notes                 VARCHAR(2000),
    status                VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    privacy_accepted_at   TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            BIGINT,
    updated_by            BIGINT,
    CONSTRAINT ck_customers_email CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE','BLOCKED')),
    CONSTRAINT ck_customers_country_code CHECK (phone_country_code ~ '^[1-9][0-9]{0,2}$')
);
CREATE UNIQUE INDEX ux_customers_hotel_email ON customers (hotel_id, email) WHERE email IS NOT NULL;
CREATE INDEX idx_customers_hotel_name ON customers (hotel_id, lower(last_name), lower(first_name));
CREATE INDEX idx_customers_hotel_phone ON customers (hotel_id, phone);

-- ── Reservaciones ─────────────────────────────────────────────────────────────────
CREATE TABLE reservations (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id             BIGINT        NOT NULL REFERENCES hotels(id),
    folio                VARCHAR(12)   NOT NULL,
    customer_id          BIGINT        NOT NULL REFERENCES customers(id),
    contact_name         VARCHAR(201)  NOT NULL,
    contact_email        VARCHAR(150),
    contact_phone        VARCHAR(25),
    check_in             DATE          NOT NULL,
    check_out            DATE          NOT NULL,
    nights               INT           NOT NULL,
    adults               INT           NOT NULL,
    children             INT           NOT NULL DEFAULT 0,
    status               VARCHAR(20)   NOT NULL,
    source               VARCHAR(15)   NOT NULL,
    room_subtotal        NUMERIC(12,2) NOT NULL,
    extra_guests_total   NUMERIC(12,2) NOT NULL DEFAULT 0,
    services_total       NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_total       NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_total            NUMERIC(12,2) NOT NULL DEFAULT 0,
    total                NUMERIC(12,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    penalty_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    price_revision       INT           NOT NULL DEFAULT 1,
    policy_version_id    BIGINT        REFERENCES hotel_policies(id),
    policy_snapshot      JSONB         NOT NULL,
    accepted_terms_at    TIMESTAMPTZ,
    special_requests     VARCHAR(1000),
    internal_notes       VARCHAR(2000),
    expires_at           TIMESTAMPTZ,
    confirmed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    cancel_reason        VARCHAR(500),
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_by           BIGINT,
    CONSTRAINT ux_reservations_folio UNIQUE (hotel_id, folio),
    CONSTRAINT ck_reservations_dates CHECK (check_out > check_in AND nights = check_out - check_in),
    CONSTRAINT ck_reservations_guests CHECK (adults >= 1 AND children >= 0),
    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING','PENDING_PAYMENT','CONFIRMED','CANCELLED','NO_SHOW','IN_HOUSE','CHECKED_OUT','EXPIRED')),
    CONSTRAINT ck_reservations_source CHECK (source IN ('WEB','FRONT_DESK','PHONE','EMAIL','OTHER')),
    CONSTRAINT ck_reservations_amounts CHECK (room_subtotal >= 0 AND services_total >= 0 AND discount_total >= 0
        AND tax_total >= 0 AND total >= 0 AND penalty_amount >= 0)
);
CREATE INDEX idx_reservations_hotel_check_in ON reservations (hotel_id, check_in);
CREATE INDEX idx_reservations_hotel_check_out ON reservations (hotel_id, check_out);
CREATE INDEX idx_reservations_hotel_status ON reservations (hotel_id, status);
CREATE INDEX idx_reservations_customer ON reservations (customer_id);

CREATE TABLE reservation_rooms (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT  NOT NULL REFERENCES hotels(id),
    reservation_id   BIGINT  NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    room_id          BIGINT  NOT NULL REFERENCES rooms(id),
    room_type_id     BIGINT  NOT NULL REFERENCES room_types(id),
    adults           INT     NOT NULL,
    children         INT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_reservation_rooms_reservation ON reservation_rooms (reservation_id);
CREATE INDEX idx_reservation_rooms_room ON reservation_rooms (room_id);

-- Desglose de precio por revisión: un cambio de fechas/habitación crea una revisión nueva; las anteriores se conservan.
CREATE TABLE reservation_price_lines (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id   BIGINT        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    revision         INT           NOT NULL,
    line_type        VARCHAR(15)   NOT NULL,
    stay_date        DATE,
    description      VARCHAR(200)  NOT NULL,
    quantity         NUMERIC(10,2) NOT NULL DEFAULT 1,
    unit_amount      NUMERIC(12,2) NOT NULL,
    amount           NUMERIC(12,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_price_lines_type CHECK (line_type IN ('NIGHT','EXTRA_GUEST','SERVICE','DISCOUNT','TAX','TAX_INCLUDED'))
);
CREATE INDEX idx_price_lines_reservation ON reservation_price_lines (reservation_id, revision);

-- Servicios contratados con precio congelado al momento de reservar.
CREATE TABLE reservation_services (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id           BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id     BIGINT        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    extra_service_id   BIGINT        NOT NULL REFERENCES extra_services(id),
    name               VARCHAR(100)  NOT NULL,
    charge_mode        VARCHAR(20)   NOT NULL,
    units              INT           NOT NULL DEFAULT 1,
    quantity           INT           NOT NULL,
    unit_price         NUMERIC(12,2) NOT NULL,
    total              NUMERIC(12,2) NOT NULL,
    CONSTRAINT ck_reservation_services_qty CHECK (units >= 1 AND quantity >= 0)
);
CREATE INDEX idx_reservation_services_reservation ON reservation_services (reservation_id);

CREATE TABLE reservation_status_history (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id   BIGINT        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    from_status      VARCHAR(20),
    to_status        VARCHAR(20)   NOT NULL,
    reason           VARCHAR(500),
    user_id          BIGINT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_history_reservation ON reservation_status_history (reservation_id, created_at);

-- Acceso del huésped sin cuenta: token de 256 bits guardado como hash. El folio solo NO basta.
CREATE TABLE reservation_access_tokens (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id   BIGINT        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    token_hash       VARCHAR(64)   NOT NULL,
    expires_at       TIMESTAMPTZ   NOT NULL,
    revoked_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_reservation_access_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_access_tokens_reservation ON reservation_access_tokens (reservation_id);

ALTER TABLE room_occupancies
    ADD CONSTRAINT fk_room_occupancies_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id) ON DELETE CASCADE;
CREATE INDEX idx_room_occupancies_reservation ON room_occupancies (reservation_id);

-- ── Correo y notificaciones ───────────────────────────────────────────────────────
CREATE TABLE email_settings (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id           BIGINT        NOT NULL REFERENCES hotels(id),
    enabled            BOOLEAN       NOT NULL DEFAULT FALSE,
    host               VARCHAR(200),
    port               INT           NOT NULL DEFAULT 587,
    username           VARCHAR(200),
    password_enc       VARCHAR(600),
    starttls           BOOLEAN       NOT NULL DEFAULT TRUE,
    from_address       VARCHAR(150),
    from_name          VARCHAR(150),
    reply_to           VARCHAR(150),
    admin_recipients   VARCHAR(1000),
    bcc_recipients     VARCHAR(1000),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_by         BIGINT,
    CONSTRAINT ux_email_settings_hotel UNIQUE (hotel_id),
    CONSTRAINT ck_email_settings_port CHECK (port BETWEEN 1 AND 65535)
);

CREATE TABLE notification_templates (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT        NOT NULL REFERENCES hotels(id),
    event_code       VARCHAR(40)   NOT NULL,
    subject          VARCHAR(200)  NOT NULL,
    body             TEXT          NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT TRUE,
    send_to_guest    BOOLEAN       NOT NULL DEFAULT TRUE,
    send_to_admins   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT ux_notification_templates_event UNIQUE (hotel_id, event_code)
);

-- Cola de salida: se escribe en la MISMA transacción de la operación (si ésta se revierte, no sale correo)
-- y un proceso la envía con reintentos. dedup_key evita correos duplicados por reintentos de la operación.
CREATE TABLE notification_outbox (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id          BIGINT        NOT NULL REFERENCES hotels(id),
    event_code        VARCHAR(40)   NOT NULL,
    dedup_key         VARCHAR(120)  NOT NULL,
    recipient         VARCHAR(150)  NOT NULL,
    subject           VARCHAR(200)  NOT NULL,
    body_html         TEXT          NOT NULL,
    body_text         TEXT          NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    attempts          INT           NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error        VARCHAR(500),
    sent_at           TIMESTAMPTZ,
    related_type      VARCHAR(40),
    related_id        BIGINT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_notification_outbox_dedup UNIQUE (hotel_id, dedup_key, recipient),
    CONSTRAINT ck_notification_outbox_status CHECK (status IN ('PENDING','SENT','FAILED','SKIPPED'))
);
CREATE INDEX idx_outbox_pending ON notification_outbox (status, next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_hotel_created ON notification_outbox (hotel_id, created_at DESC);
