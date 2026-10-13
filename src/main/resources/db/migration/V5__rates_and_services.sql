-- =====================================================================================
-- Nexora Hotel — V5: temporadas, reglas de tarifa, impuestos y servicios adicionales.
-- Importes NUMERIC(12,2) (BigDecimal); porcentajes NUMERIC(5,2).
-- =====================================================================================

CREATE TABLE seasons (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id    BIGINT        NOT NULL REFERENCES hotels(id),
    name        VARCHAR(80)   NOT NULL,
    kind        VARCHAR(10)   NOT NULL,
    starts_on   DATE          NOT NULL,
    ends_on     DATE          NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  BIGINT,
    updated_by  BIGINT,
    CONSTRAINT ck_seasons_kind CHECK (kind IN ('HIGH','LOW','HOLIDAY')),
    CONSTRAINT ck_seasons_dates CHECK (ends_on >= starts_on)
);
CREATE INDEX idx_seasons_hotel_dates ON seasons (hotel_id, starts_on, ends_on);

-- Por noche gana UNA regla: DATE_OVERRIDE > HOLIDAY > SEASON > WEEKEND > precio base; empate → priority
-- mayor → la más reciente (docs/01 §C.4). Se guarda el desglose aplicado en cada reservación (Fase 6).
CREATE TABLE rate_rules (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id          BIGINT        NOT NULL REFERENCES hotels(id),
    name              VARCHAR(100)  NOT NULL,
    room_type_id      BIGINT        REFERENCES room_types(id) ON DELETE CASCADE,
    rule_type         VARCHAR(15)   NOT NULL,
    season_id         BIGINT        REFERENCES seasons(id) ON DELETE CASCADE,
    date_from         DATE,
    date_to           DATE,
    days_of_week      VARCHAR(60),
    adjustment_type   VARCHAR(12)   NOT NULL,
    adjustment_value  NUMERIC(12,2) NOT NULL,
    min_nights        INT,
    priority          INT           NOT NULL DEFAULT 0,
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        BIGINT,
    updated_by        BIGINT,
    CONSTRAINT ck_rate_rules_type CHECK (rule_type IN ('DATE_OVERRIDE','HOLIDAY','SEASON','WEEKEND')),
    CONSTRAINT ck_rate_rules_adjustment CHECK (adjustment_type IN ('FIXED_PRICE','PERCENT','AMOUNT')),
    CONSTRAINT ck_rate_rules_fixed_positive CHECK (adjustment_type <> 'FIXED_PRICE' OR adjustment_value > 0),
    CONSTRAINT ck_rate_rules_percent CHECK (adjustment_type <> 'PERCENT' OR adjustment_value BETWEEN -90 AND 500),
    CONSTRAINT ck_rate_rules_season CHECK (rule_type <> 'SEASON' OR season_id IS NOT NULL),
    CONSTRAINT ck_rate_rules_dates CHECK (rule_type NOT IN ('DATE_OVERRIDE','HOLIDAY')
        OR (date_from IS NOT NULL AND date_to IS NOT NULL AND date_to >= date_from)),
    CONSTRAINT ck_rate_rules_weekend CHECK (rule_type <> 'WEEKEND' OR days_of_week IS NOT NULL),
    CONSTRAINT ck_rate_rules_min_nights CHECK (min_nights IS NULL OR min_nights BETWEEN 1 AND 60)
);
CREATE INDEX idx_rate_rules_hotel ON rate_rules (hotel_id) WHERE active;

CREATE TABLE tax_rates (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id            BIGINT        NOT NULL REFERENCES hotels(id),
    name                VARCHAR(60)   NOT NULL,
    percent             NUMERIC(5,2)  NOT NULL,
    applies_to          VARCHAR(10)   NOT NULL,
    included_in_price   BOOLEAN       NOT NULL DEFAULT FALSE,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_by          BIGINT,
    CONSTRAINT ck_tax_rates_percent CHECK (percent BETWEEN 0 AND 100),
    CONSTRAINT ck_tax_rates_applies CHECK (applies_to IN ('ROOM','SERVICE','ALL'))
);

CREATE TABLE extra_services (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id         BIGINT        NOT NULL REFERENCES hotels(id),
    name             VARCHAR(100)  NOT NULL,
    description      VARCHAR(1000),
    price            NUMERIC(12,2) NOT NULL,
    charge_mode      VARCHAR(20)   NOT NULL,
    bookable_online  BOOLEAN       NOT NULL DEFAULT TRUE,
    icon             VARCHAR(40)   NOT NULL DEFAULT 'concierge-bell',
    sort_order       INT           NOT NULL DEFAULT 0,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_by       BIGINT,
    CONSTRAINT ck_extra_services_price CHECK (price >= 0),
    CONSTRAINT ck_extra_services_mode CHECK (charge_mode IN ('PER_STAY','PER_NIGHT','PER_PERSON','PER_PERSON_NIGHT','PER_UNIT')),
    CONSTRAINT ck_extra_services_icon CHECK (icon ~ '^[a-z0-9-]{2,40}$')
);
CREATE UNIQUE INDEX ux_extra_services_name ON extra_services (hotel_id, lower(name));

-- Impuestos iniciales editables (el ISH varía por estado: el administrador debe ajustarlo).
INSERT INTO tax_rates (hotel_id, name, percent, applies_to, included_in_price)
SELECT id, 'IVA', 16.00, 'ALL', FALSE FROM hotels;
INSERT INTO tax_rates (hotel_id, name, percent, applies_to, included_in_price)
SELECT id, 'ISH (hospedaje)', 3.00, 'ROOM', FALSE FROM hotels;
