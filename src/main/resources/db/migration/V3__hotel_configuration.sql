-- =====================================================================================
-- Nexora Hotel — V3: configuración del hotel (operación, identidad visual, políticas).
-- Todo lo que el administrador puede cambiar vive aquí; nada de esto va en variables de entorno.
-- =====================================================================================

-- Parámetros operativos (1:1 con el hotel).
CREATE TABLE hotel_settings (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id                   BIGINT       NOT NULL REFERENCES hotels(id),
    check_in_time              TIME         NOT NULL DEFAULT '15:00',
    check_out_time             TIME         NOT NULL DEFAULT '12:00',
    reception_opens            TIME         NOT NULL DEFAULT '00:00',
    reception_closes           TIME         NOT NULL DEFAULT '23:59',
    reception_24h              BOOLEAN      NOT NULL DEFAULT TRUE,
    min_booking_lead_hours     INT          NOT NULL DEFAULT 0,
    max_booking_lead_days      INT          NOT NULL DEFAULT 365,
    min_nights                 INT          NOT NULL DEFAULT 1,
    max_nights                 INT          NOT NULL DEFAULT 30,
    max_rooms_per_booking      INT          NOT NULL DEFAULT 5,
    hold_minutes               INT          NOT NULL DEFAULT 15,
    allow_same_day_booking     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by                 BIGINT,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by                 BIGINT,
    CONSTRAINT ux_hotel_settings_hotel UNIQUE (hotel_id),
    CONSTRAINT ck_hotel_settings_lead CHECK (min_booking_lead_hours BETWEEN 0 AND 720 AND max_booking_lead_days BETWEEN 1 AND 730),
    CONSTRAINT ck_hotel_settings_nights CHECK (min_nights >= 1 AND max_nights >= min_nights AND max_nights <= 365),
    CONSTRAINT ck_hotel_settings_rooms CHECK (max_rooms_per_booking BETWEEN 1 AND 50),
    CONSTRAINT ck_hotel_settings_hold CHECK (hold_minutes BETWEEN 5 AND 120)
);

-- Identidad pública y tema (1:1). El tema son colores #RRGGBB validados; nunca CSS libre.
CREATE TABLE hotel_branding (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id           BIGINT        NOT NULL REFERENCES hotels(id),
    tagline            VARCHAR(160),
    description        VARCHAR(2000),
    legal_name         VARCHAR(200),
    rfc                VARCHAR(13),
    street             VARCHAR(200),
    neighborhood       VARCHAR(120),
    city               VARCHAR(120),
    state              VARCHAR(120),
    postal_code        VARCHAR(10),
    country            VARCHAR(2)    NOT NULL DEFAULT 'MX',
    latitude           NUMERIC(9,6),
    longitude          NUMERIC(9,6),
    phone              VARCHAR(20),
    whatsapp           VARCHAR(20),
    contact_email      VARCHAR(150),
    website            VARCHAR(255),
    facebook           VARCHAR(255),
    instagram          VARCHAR(255),
    tiktok             VARCHAR(255),
    logo_file_id       BIGINT        REFERENCES stored_files(id),
    logo_alt_file_id   BIGINT        REFERENCES stored_files(id),
    favicon_file_id    BIGINT        REFERENCES stored_files(id),
    hero_file_id       BIGINT        REFERENCES stored_files(id),
    theme              JSONB         NOT NULL DEFAULT '{}'::jsonb,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         BIGINT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         BIGINT,
    CONSTRAINT ux_hotel_branding_hotel UNIQUE (hotel_id),
    CONSTRAINT ck_hotel_branding_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_hotel_branding_lng CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

-- Políticas versionadas: cada cambio inserta una versión nueva. Las reservaciones guardan la versión que
-- aceptaron (y una copia), así un cambio nunca altera condiciones ya contratadas.
CREATE TABLE hotel_policies (
    id                              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id                        BIGINT        NOT NULL REFERENCES hotels(id),
    version                         INT           NOT NULL,
    free_cancel_hours               INT           NOT NULL DEFAULT 48,
    late_cancel_penalty_type        VARCHAR(15)   NOT NULL DEFAULT 'FIRST_NIGHT',
    late_cancel_penalty_value       NUMERIC(12,2) NOT NULL DEFAULT 0,
    deposit_percent                 NUMERIC(5,2)  NOT NULL DEFAULT 30,
    pending_payment_expiry_minutes  INT           NOT NULL DEFAULT 30,
    no_show_penalty_type            VARCHAR(15)   NOT NULL DEFAULT 'FIRST_NIGHT',
    no_show_penalty_value           NUMERIC(12,2) NOT NULL DEFAULT 0,
    children_policy                 VARCHAR(2000),
    pets_allowed                    BOOLEAN       NOT NULL DEFAULT FALSE,
    pets_policy                     VARCHAR(2000),
    refund_policy                   VARCHAR(2000),
    date_change_policy              VARCHAR(2000),
    terms                           TEXT,
    privacy_notice                  TEXT,
    created_at                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by                      BIGINT,
    CONSTRAINT ux_hotel_policies_version UNIQUE (hotel_id, version),
    CONSTRAINT ck_hotel_policies_penalty CHECK (late_cancel_penalty_type IN ('NONE','FIRST_NIGHT','PERCENT','FIXED')
        AND no_show_penalty_type IN ('NONE','FIRST_NIGHT','PERCENT','FIXED')),
    CONSTRAINT ck_hotel_policies_values CHECK (late_cancel_penalty_value >= 0 AND no_show_penalty_value >= 0
        AND deposit_percent BETWEEN 0 AND 100 AND free_cancel_hours BETWEEN 0 AND 8760
        AND pending_payment_expiry_minutes BETWEEN 5 AND 1440)
);
CREATE TRIGGER trg_hotel_policies_immutable
    BEFORE UPDATE OR DELETE ON hotel_policies
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();

-- Hoteles existentes: su configuración inicial.
INSERT INTO hotel_settings (hotel_id) SELECT id FROM hotels;
INSERT INTO hotel_branding (hotel_id) SELECT id FROM hotels;
INSERT INTO hotel_policies (hotel_id, version) SELECT id, 1 FROM hotels;

-- Archivos: el favicon también puede ser PNG; se permite además SVG solo como tipo conocido (no se acepta en subida).
CREATE INDEX idx_stored_files_hotel_owner ON stored_files (hotel_id, owner_type, owner_id) WHERE deleted_at IS NULL;
