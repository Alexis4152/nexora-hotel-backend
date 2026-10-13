-- =====================================================================================
-- Nexora Hotel — V4: inventario de habitaciones, fotografías, bloqueos y limpieza.
-- =====================================================================================

CREATE TABLE amenities (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id    BIGINT       NOT NULL REFERENCES hotels(id),
    name        VARCHAR(80)  NOT NULL,
    icon        VARCHAR(40)  NOT NULL DEFAULT 'sparkles',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_by  BIGINT,
    CONSTRAINT ck_amenities_icon CHECK (icon ~ '^[a-z0-9-]{2,40}$')
);
CREATE UNIQUE INDEX ux_amenities_hotel_name ON amenities (hotel_id, lower(name));

CREATE TABLE room_types (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id             BIGINT        NOT NULL REFERENCES hotels(id),
    code                 VARCHAR(20)   NOT NULL,
    name                 VARCHAR(100)  NOT NULL,
    description          VARCHAR(3000),
    base_price           NUMERIC(12,2) NOT NULL,
    max_adults           INT           NOT NULL,
    max_children         INT           NOT NULL DEFAULT 0,
    max_occupancy        INT           NOT NULL,
    included_occupancy   INT           NOT NULL DEFAULT 2,
    extra_adult_fee      NUMERIC(12,2) NOT NULL DEFAULT 0,
    extra_child_fee      NUMERIC(12,2) NOT NULL DEFAULT 0,
    bed_count            INT           NOT NULL DEFAULT 1,
    bed_type             VARCHAR(40)   NOT NULL DEFAULT 'MATRIMONIAL',
    size_m2              NUMERIC(6,1),
    sort_order           INT           NOT NULL DEFAULT 0,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_by           BIGINT,
    CONSTRAINT ux_room_types_code UNIQUE (hotel_id, code),
    CONSTRAINT ck_room_types_price CHECK (base_price > 0 AND extra_adult_fee >= 0 AND extra_child_fee >= 0),
    CONSTRAINT ck_room_types_capacity CHECK (max_adults BETWEEN 1 AND 20 AND max_children BETWEEN 0 AND 20
        AND max_occupancy BETWEEN 1 AND 30 AND max_occupancy <= max_adults + max_children
        AND included_occupancy BETWEEN 1 AND max_occupancy AND bed_count BETWEEN 1 AND 10),
    CONSTRAINT ck_room_types_code CHECK (code ~ '^[A-Z0-9_-]{2,20}$')
);

CREATE TABLE room_type_amenities (
    room_type_id  BIGINT NOT NULL REFERENCES room_types(id) ON DELETE CASCADE,
    amenity_id    BIGINT NOT NULL REFERENCES amenities(id) ON DELETE CASCADE,
    PRIMARY KEY (room_type_id, amenity_id)
);

CREATE TABLE rooms (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id             BIGINT        NOT NULL REFERENCES hotels(id),
    room_type_id         BIGINT        NOT NULL REFERENCES room_types(id),
    number               VARCHAR(10)   NOT NULL,
    name                 VARCHAR(100),
    floor                VARCHAR(10),
    description          VARCHAR(2000),
    internal_notes       VARCHAR(2000),
    operational_status   VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    sort_order           INT           NOT NULL DEFAULT 0,
    active               BOOLEAN       NOT NULL DEFAULT TRUE,
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_by           BIGINT,
    CONSTRAINT ux_rooms_number UNIQUE (hotel_id, number),
    CONSTRAINT ck_rooms_status CHECK (operational_status IN ('AVAILABLE','OCCUPIED','CLEANING','MAINTENANCE','OUT_OF_SERVICE')),
    CONSTRAINT ck_rooms_number CHECK (number ~ '^[A-Za-z0-9-]{1,10}$')
);
CREATE INDEX idx_rooms_type ON rooms (room_type_id);
CREATE INDEX idx_rooms_hotel_status ON rooms (hotel_id, operational_status) WHERE active;

-- Fotografías de un tipo de habitación o de una habitación concreta (exactamente uno de los dos).
CREATE TABLE room_images (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id       BIGINT       NOT NULL REFERENCES hotels(id),
    room_type_id   BIGINT       REFERENCES room_types(id) ON DELETE CASCADE,
    room_id        BIGINT       REFERENCES rooms(id) ON DELETE CASCADE,
    file_id        BIGINT       NOT NULL REFERENCES stored_files(id),
    sort_order     INT          NOT NULL DEFAULT 0,
    alt_text       VARCHAR(160),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_room_images_owner CHECK ((room_type_id IS NULL) <> (room_id IS NULL))
);
CREATE INDEX idx_room_images_type ON room_images (room_type_id, sort_order);
CREATE INDEX idx_room_images_room ON room_images (room_id, sort_order);

-- Mantenimiento: motivo, periodo y liberación autorizada.
CREATE TABLE maintenance_records (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id       BIGINT        NOT NULL REFERENCES hotels(id),
    room_id        BIGINT        NOT NULL REFERENCES rooms(id),
    reason         VARCHAR(500)  NOT NULL,
    starts_on      DATE          NOT NULL,
    ends_on        DATE          NOT NULL,
    status         VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     BIGINT,
    released_at    TIMESTAMPTZ,
    released_by    BIGINT,
    release_notes  VARCHAR(500),
    CONSTRAINT ck_maintenance_dates CHECK (ends_on > starts_on),
    CONSTRAINT ck_maintenance_status CHECK (status IN ('OPEN','RELEASED'))
);
CREATE INDEX idx_maintenance_room ON maintenance_records (room_id, starts_on DESC);

-- Todo lo que ocupa una habitación en un rango de fechas: reservaciones, retenciones de pago y bloqueos.
-- La restricción EXCLUDE es la garantía final contra la doble reservación, incluso con peticiones simultáneas.
-- El rango es [entrada, salida): la fecha de salida NO es una noche ocupada.
CREATE TABLE room_occupancies (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT       NOT NULL REFERENCES hotels(id),
    room_id          BIGINT       NOT NULL REFERENCES rooms(id),
    stay             DATERANGE    NOT NULL,
    kind             VARCHAR(12)  NOT NULL,
    reservation_id   BIGINT,
    maintenance_id   BIGINT       REFERENCES maintenance_records(id),
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_room_occupancies_kind CHECK (kind IN ('RESERVATION','HOLD','BLOCK')),
    CONSTRAINT ck_room_occupancies_stay CHECK (NOT isempty(stay) AND lower_inc(stay) AND NOT upper_inc(stay)),
    CONSTRAINT ck_room_occupancies_ref CHECK (
        (kind = 'BLOCK' AND maintenance_id IS NOT NULL) OR (kind <> 'BLOCK' AND reservation_id IS NOT NULL)),
    CONSTRAINT ex_room_occupancies_no_overlap EXCLUDE USING gist (room_id WITH =, stay WITH &&)
);
CREATE INDEX idx_room_occupancies_hotel_stay ON room_occupancies USING gist (hotel_id, stay);

-- Bitácora de limpieza / estado operativo.
CREATE TABLE housekeeping_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id      BIGINT       NOT NULL REFERENCES hotels(id),
    room_id       BIGINT       NOT NULL REFERENCES rooms(id),
    from_status   VARCHAR(20)  NOT NULL,
    to_status     VARCHAR(20)  NOT NULL,
    notes         VARCHAR(500),
    user_id       BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_housekeeping_room ON housekeeping_logs (room_id, created_at DESC);
