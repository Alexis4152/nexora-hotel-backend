-- =====================================================================================
-- Nexora Hotel — V1: núcleo multi-hotel, seguridad, archivos y auditoría.
-- Ver docs/02-MODELO-DE-DATOS.md. Hibernate solo valida (ddl-auto=validate).
-- =====================================================================================

-- Necesaria desde ya para las restricciones EXCLUDE de room_occupancies (traslape de
-- fechas). En PostgreSQL 13+ es "trusted": la crea el dueño de la base sin ser superusuario.
-- Se crea aquí para que un servidor sin ella falle en el primer despliegue, no en la Fase 6.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Función genérica para tablas de solo inserción (auditoría, libro de pagos).
CREATE OR REPLACE FUNCTION nx_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es de solo inserción: % no permitido', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

-- ── Hoteles (tenants) ────────────────────────────────────────────────────────────────
CREATE TABLE hotels (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug            VARCHAR(60)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    timezone        VARCHAR(60)  NOT NULL DEFAULT 'America/Mexico_City',
    currency        VARCHAR(3)   NOT NULL DEFAULT 'MXN',
    custom_domain   VARCHAR(255),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_hotels_slug UNIQUE (slug),
    CONSTRAINT ux_hotels_custom_domain UNIQUE (custom_domain),
    CONSTRAINT ck_hotels_slug CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,58})[a-z0-9]$'),
    CONSTRAINT ck_hotels_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- ── Catálogo global de permisos ──────────────────────────────────────────────────────
CREATE TABLE permissions (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                  VARCHAR(60)  NOT NULL,
    module                VARCHAR(30)  NOT NULL,
    action                VARCHAR(30)  NOT NULL,
    description           VARCHAR(200) NOT NULL,
    -- Solo asignable al rol de sistema ADMIN (docs/03-PERMISOS.md).
    reserved              BOOLEAN      NOT NULL DEFAULT FALSE,
    sensitive             BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Incluido en el rol RECEPTIONIST que se siembra al crear un hotel.
    default_receptionist  BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order            INT          NOT NULL DEFAULT 0,
    CONSTRAINT ux_permissions_code UNIQUE (code),
    CONSTRAINT ck_permissions_code CHECK (code = module || ':' || action),
    CONSTRAINT ck_permissions_reserved_not_default CHECK (NOT (reserved AND default_receptionist))
);

-- ── Usuarios ─────────────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL solo para usuarios de plataforma (SUPER_ADMIN de Nexora).
    hotel_id               BIGINT       REFERENCES hotels(id),
    email                  VARCHAR(150) NOT NULL,
    password_hash          VARCHAR(100) NOT NULL,
    first_name             VARCHAR(100) NOT NULL,
    last_name              VARCHAR(100) NOT NULL,
    phone                  VARCHAR(20),
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password   BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           TIMESTAMPTZ,
    last_login_at          TIMESTAMPTZ,
    -- Se incrementa al cambiar roles/permisos del usuario: invalida su caché de permisos.
    permissions_version    INT          NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_by             BIGINT,
    deleted_at             TIMESTAMPTZ,
    deleted_by             BIGINT,
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
);
-- El mismo correo puede tener cuenta en hoteles distintos, pero no dos veces en el mismo.
CREATE UNIQUE INDEX ux_users_hotel_email ON users (hotel_id, email) WHERE hotel_id IS NOT NULL;
CREATE UNIQUE INDEX ux_users_platform_email ON users (email) WHERE hotel_id IS NULL;
CREATE INDEX idx_users_hotel ON users (hotel_id);

-- ── Roles (por hotel) ────────────────────────────────────────────────────────────────
CREATE TABLE roles (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL solo para el rol de plataforma SUPER_ADMIN.
    hotel_id      BIGINT       REFERENCES hotels(id),
    code          VARCHAR(40)  NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    description   VARCHAR(200),
    system        BOOLEAN      NOT NULL DEFAULT FALSE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_by    BIGINT,
    CONSTRAINT ck_roles_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    CONSTRAINT ck_roles_platform CHECK ((hotel_id IS NULL) = (code = 'SUPER_ADMIN'))
);
CREATE UNIQUE INDEX ux_roles_hotel_code ON roles (hotel_id, code) WHERE hotel_id IS NOT NULL;
CREATE UNIQUE INDEX ux_roles_platform_code ON roles (code) WHERE hotel_id IS NULL;

CREATE TABLE role_permissions (
    role_id        BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id  BIGINT NOT NULL REFERENCES permissions(id),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

CREATE TABLE user_roles (
    user_id  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

-- Ajustes puntuales por usuario sobre lo que le dan sus roles. DENY gana sobre GRANT.
CREATE TABLE user_permission_overrides (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id       BIGINT       NOT NULL REFERENCES hotels(id),
    user_id        BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission_id  BIGINT       NOT NULL REFERENCES permissions(id),
    effect         VARCHAR(10)  NOT NULL,
    reason         VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     BIGINT,
    CONSTRAINT ux_user_permission_overrides UNIQUE (user_id, permission_id),
    CONSTRAINT ck_user_permission_overrides_effect CHECK (effect IN ('GRANT', 'DENY'))
);
CREATE INDEX idx_user_permission_overrides_hotel ON user_permission_overrides (hotel_id);

-- ── Tokens de sesión y recuperación (solo se guarda el hash SHA-256) ─────────────────
CREATE TABLE refresh_tokens (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL,
    -- Todos los tokens de una misma sesión (rotaciones sucesivas) comparten familia: si se
    -- reutiliza uno ya rotado, se revoca la familia completa (posible robo de cookie).
    family_id       UUID         NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  BIGINT       REFERENCES refresh_tokens(id),
    ip              VARCHAR(45),
    user_agent      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE password_reset_tokens (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_password_reset_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);

-- ── Archivos (las imágenes viven en APP_STORAGE_DIR, aquí solo los metadatos) ────────
CREATE TABLE stored_files (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id        BIGINT        NOT NULL REFERENCES hotels(id),
    storage_key     VARCHAR(300)  NOT NULL,
    original_name   VARCHAR(255),
    mime_type       VARCHAR(50)   NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    sha256          VARCHAR(64)   NOT NULL,
    width           INT,
    height          INT,
    owner_type      VARCHAR(40),
    owner_id        BIGINT,
    uploaded_by     BIGINT        REFERENCES users(id),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ux_stored_files_key UNIQUE (storage_key),
    CONSTRAINT ck_stored_files_size CHECK (size_bytes > 0),
    CONSTRAINT ck_stored_files_mime CHECK (mime_type IN ('image/jpeg', 'image/png', 'image/webp', 'image/x-icon'))
);
CREATE INDEX idx_stored_files_hotel ON stored_files (hotel_id);
CREATE INDEX idx_stored_files_owner ON stored_files (owner_type, owner_id);

-- ── Bitácora de auditoría (solo inserción) ───────────────────────────────────────────
CREATE TABLE audit_logs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL = evento de plataforma (SUPER_ADMIN).
    hotel_id        BIGINT        REFERENCES hotels(id),
    actor_user_id   BIGINT,
    actor_email     VARCHAR(150),
    action          VARCHAR(60)   NOT NULL,
    entity_type     VARCHAR(60),
    entity_id       VARCHAR(60),
    before_data     JSONB,
    after_data      JSONB,
    ip              VARCHAR(45),
    user_agent      VARCHAR(255),
    result          VARCHAR(10)   NOT NULL,
    detail          VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED'))
);
CREATE INDEX idx_audit_logs_hotel_created ON audit_logs (hotel_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id, created_at DESC);

CREATE TRIGGER trg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION nx_forbid_mutation();
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION nx_forbid_mutation();
