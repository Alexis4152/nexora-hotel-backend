-- Notificaciones en el panel (campanita) para el personal del hotel. Una fila por destinatario: cada quien marca
-- las suyas como leídas. Se guardan el tipo y sus valores (no el texto ya redactado) para que la aplicación las
-- muestre en el idioma de quien las lee.
CREATE TABLE staff_notifications (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hotel_id      BIGINT        NOT NULL REFERENCES hotels(id),
    user_id       BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type          VARCHAR(40)   NOT NULL,
    category      VARCHAR(20)   NOT NULL,
    severity      VARCHAR(10)   NOT NULL DEFAULT 'INFO',
    params        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    link          VARCHAR(300),
    related_type  VARCHAR(40),
    related_id    BIGINT,
    dedup_key     VARCHAR(120)  NOT NULL,
    read_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_staff_notifications_category CHECK (category IN ('RESERVATIONS','PAYMENTS','OPERATIONS','CUSTOMERS')),
    CONSTRAINT ck_staff_notifications_severity CHECK (severity IN ('INFO','SUCCESS','WARNING','DANGER'))
);
-- El mismo aviso nunca llega dos veces a la misma persona (reintentos, tareas programadas que corren de nuevo).
CREATE UNIQUE INDEX ux_staff_notifications_dedup ON staff_notifications (user_id, dedup_key);
CREATE INDEX idx_staff_notifications_user ON staff_notifications (user_id, created_at DESC);
CREATE INDEX idx_staff_notifications_unread ON staff_notifications (user_id) WHERE read_at IS NULL;
CREATE INDEX idx_staff_notifications_created ON staff_notifications (created_at);
