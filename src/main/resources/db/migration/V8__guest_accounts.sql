-- Fase 8: cuentas de huésped en el portal (registro con verificación de correo) y solicitudes de datos personales.

-- Un huésped solo puede entrar con su correo verificado: así nadie se adueña de las reservaciones de otra persona
-- registrando su correo. El personal (y toda cuenta creada por otra vía) nace verificado; solo el registro público
-- de huéspedes lo deja vacío hasta confirmar el enlace.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ DEFAULT now();
UPDATE users SET email_verified_at = created_at;
-- Lada del teléfono del huésped (se copia a su perfil de cliente al verificar el correo).
ALTER TABLE users ADD COLUMN phone_country_code VARCHAR(4);

CREATE TABLE email_verification_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id    BIGINT       NOT NULL REFERENCES hotels(id),
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_email_verification_hash UNIQUE (token_hash)
);
CREATE INDEX idx_email_verification_user ON email_verification_tokens (user_id);

-- Una cuenta corresponde a un solo cliente del hotel (y viceversa).
CREATE UNIQUE INDEX ux_customers_user ON customers (user_id) WHERE user_id IS NOT NULL;
ALTER TABLE customers ADD COLUMN anonymized_at TIMESTAMPTZ;

-- Derechos ARCO (LFPDPPP): acceso, rectificación, cancelación (eliminación) y oposición.
CREATE TABLE data_requests (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id          BIGINT        NOT NULL REFERENCES hotels(id),
    customer_id       BIGINT        NOT NULL REFERENCES customers(id),
    user_id           BIGINT        REFERENCES users(id),
    type              VARCHAR(15)   NOT NULL,
    details           VARCHAR(2000),
    status            VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    resolution_notes  VARCHAR(2000),
    resolved_by       BIGINT        REFERENCES users(id),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_data_requests_type CHECK (type IN ('ACCESS','RECTIFICATION','DELETION','OPPOSITION')),
    CONSTRAINT ck_data_requests_status CHECK (status IN ('PENDING','COMPLETED','REJECTED')),
    CONSTRAINT ck_data_requests_resolved CHECK (status = 'PENDING' OR (resolved_at IS NOT NULL AND resolution_notes IS NOT NULL))
);
CREATE INDEX idx_data_requests_hotel_status ON data_requests (hotel_id, status, created_at DESC);
