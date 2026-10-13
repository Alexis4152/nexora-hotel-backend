-- Fase 7: pagos (presenciales y en línea), reembolsos, caja, cargos, check-in / check-out y no-show.
--
-- Reglas financieras que garantiza la base, no solo la aplicación:
--  * Un pago nunca se borra. Una vez fuera de PENDIENTE no cambia; importe, tipo, método y reservación jamás cambian.
--    Las correcciones son filas nuevas (REVERSAL / REFUND) ligadas al pago original.
--  * Los movimientos de caja son de solo inserción y solo se aceptan en una caja ABIERTA.
--  * Una caja cerrada ya no cambia. Solo puede haber una caja abierta por usuario y hotel.
--  * Los cargos a la cuenta no se borran: se anulan una sola vez con motivo.

-- ── Reservación: cuenta y operación ──────────────────────────────────────────────────
ALTER TABLE reservations
    ADD COLUMN charges_total    NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN paid_total       NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN deposit_required NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN checked_in_at    TIMESTAMPTZ,
    ADD COLUMN checked_in_by    BIGINT REFERENCES users(id),
    ADD COLUMN check_in_notes   VARCHAR(1000),
    ADD COLUMN checked_out_at   TIMESTAMPTZ,
    ADD COLUMN checked_out_by   BIGINT REFERENCES users(id),
    ADD COLUMN check_out_notes  VARCHAR(1000),
    ADD COLUMN no_show_at       TIMESTAMPTZ;
ALTER TABLE reservations ADD CONSTRAINT ck_reservations_account
    CHECK (charges_total >= 0 AND paid_total >= 0 AND deposit_required >= 0);
ALTER TABLE reservations ADD CONSTRAINT ck_reservations_stay_times
    CHECK ((checked_out_at IS NULL OR checked_in_at IS NOT NULL) AND (checked_out_at IS NULL OR checked_out_at >= checked_in_at));

-- ── Configuración de cobro por hotel ─────────────────────────────────────────────────
CREATE TABLE payment_settings (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id                 BIGINT        NOT NULL REFERENCES hotels(id),
    cash_enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    card_enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    transfer_enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    online_enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
    provider                 VARCHAR(20)   NOT NULL DEFAULT 'OPENPAY',
    sandbox                  BOOLEAN       NOT NULL DEFAULT TRUE,
    merchant_id              VARCHAR(40),
    private_key_enc          VARCHAR(600),
    public_key               VARCHAR(100),
    webhook_user             VARCHAR(60),
    webhook_password_enc     VARCHAR(600),
    webhook_verification     VARCHAR(40),
    transfer_instructions    VARCHAR(1000),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_by               BIGINT,
    CONSTRAINT ux_payment_settings_hotel UNIQUE (hotel_id),
    CONSTRAINT ck_payment_settings_provider CHECK (provider IN ('OPENPAY'))
);

-- ── Caja de recepción ───────────────────────────────────────────────────────────────
CREATE TABLE cash_sessions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id        BIGINT        NOT NULL REFERENCES hotels(id),
    user_id         BIGINT        NOT NULL REFERENCES users(id),
    status          VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    opening_float   NUMERIC(12,2) NOT NULL,
    opening_notes   VARCHAR(500),
    opened_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at       TIMESTAMPTZ,
    closed_by       BIGINT        REFERENCES users(id),
    expected_cash   NUMERIC(12,2),
    counted_cash    NUMERIC(12,2),
    difference      NUMERIC(12,2),
    closing_notes   VARCHAR(500),
    totals          JSONB,
    CONSTRAINT ck_cash_sessions_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT ck_cash_sessions_amounts CHECK (opening_float >= 0 AND (counted_cash IS NULL OR counted_cash >= 0)),
    CONSTRAINT ck_cash_sessions_closed CHECK (status = 'OPEN' OR (closed_at IS NOT NULL AND counted_cash IS NOT NULL
        AND expected_cash IS NOT NULL AND difference IS NOT NULL))
);
CREATE UNIQUE INDEX ux_cash_sessions_open_per_user ON cash_sessions (hotel_id, user_id) WHERE status = 'OPEN';
CREATE INDEX idx_cash_sessions_hotel_opened ON cash_sessions (hotel_id, opened_at DESC);

CREATE OR REPLACE FUNCTION nx_cash_session_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Las cajas no se eliminan' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'La caja % ya está cerrada', OLD.id USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.opening_float <> OLD.opening_float OR NEW.user_id <> OLD.user_id OR NEW.opened_at <> OLD.opened_at THEN
        RAISE EXCEPTION 'Los datos de apertura de una caja no cambian' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_cash_sessions_guard BEFORE UPDATE OR DELETE ON cash_sessions
    FOR EACH ROW EXECUTE FUNCTION nx_cash_session_guard();

-- ── Pagos, reembolsos y reversos ─────────────────────────────────────────────────────
CREATE TABLE payments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id            BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id      BIGINT        NOT NULL REFERENCES reservations(id),
    kind                VARCHAR(10)   NOT NULL,
    method              VARCHAR(10)   NOT NULL,
    status              VARCHAR(10)   NOT NULL,
    amount              NUMERIC(12,2) NOT NULL,
    currency            VARCHAR(3)    NOT NULL DEFAULT 'MXN',
    reference           VARCHAR(100),
    notes               VARCHAR(500),
    related_payment_id  BIGINT        REFERENCES payments(id),
    cash_session_id     BIGINT        REFERENCES cash_sessions(id),
    idempotency_key     VARCHAR(80),
    provider            VARCHAR(20),
    provider_charge_id  VARCHAR(80),
    provider_order_id   VARCHAR(80),
    provider_status     VARCHAR(30),
    checkout_url        VARCHAR(600),
    public_ref          VARCHAR(64),
    expires_at          TIMESTAMPTZ,
    failure_reason      VARCHAR(300),
    card_brand          VARCHAR(30),
    card_last4          VARCHAR(4),
    created_by          BIGINT        REFERENCES users(id),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT ck_payments_kind CHECK (kind IN ('PAYMENT','REFUND','REVERSAL')),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH','CARD','TRANSFER','ONLINE')),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING','COMPLETED','FAILED','CANCELLED')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_reversal_ref CHECK (kind <> 'REVERSAL' OR related_payment_id IS NOT NULL),
    CONSTRAINT ck_payments_online CHECK (method <> 'ONLINE' OR provider IS NOT NULL),
    CONSTRAINT ck_payments_last4 CHECK (card_last4 IS NULL OR card_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_payments_completed CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
CREATE INDEX idx_payments_reservation ON payments (reservation_id, created_at);
CREATE INDEX idx_payments_hotel_created ON payments (hotel_id, created_at DESC);
CREATE INDEX idx_payments_pending_online ON payments (hotel_id, created_at) WHERE status = 'PENDING' AND method = 'ONLINE';
CREATE UNIQUE INDEX ux_payments_idempotency ON payments (hotel_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX ux_payments_provider_charge ON payments (provider, provider_charge_id) WHERE provider_charge_id IS NOT NULL;
CREATE UNIQUE INDEX ux_payments_provider_order ON payments (provider, provider_order_id) WHERE provider_order_id IS NOT NULL;
CREATE UNIQUE INDEX ux_payments_public_ref ON payments (public_ref) WHERE public_ref IS NOT NULL;

CREATE OR REPLACE FUNCTION nx_payment_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Los pagos no se eliminan: registra un reverso o un reembolso' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.amount <> OLD.amount OR NEW.kind <> OLD.kind OR NEW.method <> OLD.method
       OR NEW.reservation_id <> OLD.reservation_id OR NEW.hotel_id <> OLD.hotel_id
       OR NEW.related_payment_id IS DISTINCT FROM OLD.related_payment_id THEN
        RAISE EXCEPTION 'Importe, tipo, método y reservación de un pago no cambian' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status <> 'PENDING' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'El pago % ya está %: no se modifica', OLD.id, OLD.status USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_payments_guard BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION nx_payment_guard();
CREATE TRIGGER trg_payments_no_truncate BEFORE TRUNCATE ON payments
    FOR EACH STATEMENT EXECUTE FUNCTION nx_forbid_mutation();

CREATE TABLE cash_movements (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id     BIGINT        NOT NULL REFERENCES hotels(id),
    session_id   BIGINT        NOT NULL REFERENCES cash_sessions(id),
    type         VARCHAR(12)   NOT NULL,
    method       VARCHAR(10)   NOT NULL,
    direction    VARCHAR(3)    NOT NULL,
    amount       NUMERIC(12,2) NOT NULL,
    payment_id   BIGINT        REFERENCES payments(id),
    reason       VARCHAR(300),
    user_id      BIGINT        NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cash_movements_type CHECK (type IN ('PAYMENT','REFUND','REVERSAL','WITHDRAWAL','DEPOSIT')),
    CONSTRAINT ck_cash_movements_method CHECK (method IN ('CASH','CARD','TRANSFER')),
    CONSTRAINT ck_cash_movements_direction CHECK (direction IN ('IN','OUT')),
    CONSTRAINT ck_cash_movements_amount CHECK (amount > 0),
    CONSTRAINT ck_cash_movements_adjust CHECK (type NOT IN ('WITHDRAWAL','DEPOSIT') OR (method = 'CASH' AND reason IS NOT NULL))
);
CREATE INDEX idx_cash_movements_session ON cash_movements (session_id, created_at);

CREATE OR REPLACE FUNCTION nx_cash_movement_open_session() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM cash_sessions s WHERE s.id = NEW.session_id AND s.status = 'OPEN' AND s.hotel_id = NEW.hotel_id) THEN
        RAISE EXCEPTION 'La caja % no está abierta', NEW.session_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_cash_movements_open BEFORE INSERT ON cash_movements
    FOR EACH ROW EXECUTE FUNCTION nx_cash_movement_open_session();
CREATE TRIGGER trg_cash_movements_immutable BEFORE UPDATE OR DELETE ON cash_movements
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();

-- ── Cargos a la cuenta (consumos, daños, servicios en estancia) ──────────────────────
CREATE TABLE reservation_charges (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id        BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id  BIGINT        NOT NULL REFERENCES reservations(id),
    service_id      BIGINT        REFERENCES extra_services(id),
    description     VARCHAR(150)  NOT NULL,
    quantity        NUMERIC(8,2)  NOT NULL,
    unit_price      NUMERIC(12,2) NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    created_by      BIGINT        REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    voided_at       TIMESTAMPTZ,
    voided_by       BIGINT        REFERENCES users(id),
    void_reason     VARCHAR(300),
    CONSTRAINT ck_reservation_charges_values CHECK (quantity > 0 AND unit_price >= 0 AND amount >= 0),
    CONSTRAINT ck_reservation_charges_void CHECK (voided_at IS NULL OR void_reason IS NOT NULL)
);
CREATE INDEX idx_reservation_charges_reservation ON reservation_charges (reservation_id);

CREATE OR REPLACE FUNCTION nx_charge_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Los cargos no se eliminan: anúlalos con motivo' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.voided_at IS NOT NULL OR NEW.amount <> OLD.amount OR NEW.quantity <> OLD.quantity
       OR NEW.unit_price <> OLD.unit_price OR NEW.description <> OLD.description OR NEW.reservation_id <> OLD.reservation_id THEN
        RAISE EXCEPTION 'Un cargo solo puede anularse una vez y no cambia su importe' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_reservation_charges_guard BEFORE UPDATE OR DELETE ON reservation_charges
    FOR EACH ROW EXECUTE FUNCTION nx_charge_guard();

-- ── Huéspedes registrados en el check-in ─────────────────────────────────────────────
CREATE TABLE reservation_guests (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id        BIGINT        NOT NULL REFERENCES hotels(id),
    reservation_id  BIGINT        NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    full_name       VARCHAR(201)  NOT NULL,
    minor           BOOLEAN       NOT NULL DEFAULT FALSE,
    id_type         VARCHAR(20),
    id_number_enc   VARCHAR(600),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_reservation_guests_id_type CHECK (id_type IS NULL OR id_type IN ('INE','PASSPORT','LICENSE','OTHER'))
);
CREATE INDEX idx_reservation_guests_reservation ON reservation_guests (reservation_id);

-- ── Webhooks recibidos (idempotencia; nunca se guarda el payload con datos de tarjeta) ─
CREATE TABLE payment_webhook_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id        BIGINT        NOT NULL REFERENCES hotels(id),
    provider        VARCHAR(20)   NOT NULL,
    event_type      VARCHAR(60)   NOT NULL,
    transaction_id  VARCHAR(80),
    dedup_key       VARCHAR(200)  NOT NULL,
    result          VARCHAR(40),
    received_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_payment_webhook_events UNIQUE (hotel_id, provider, dedup_key)
);

-- Configuración de cobro para los hoteles existentes (pagos en recepción activos; en línea apagado).
INSERT INTO payment_settings (hotel_id) SELECT id FROM hotels;
