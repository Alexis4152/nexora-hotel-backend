# Modelo de datos (PostgreSQL)

Convenciones:

- PK `id BIGINT GENERATED ALWAYS AS IDENTITY`.
- Toda tabla de negocio: `hotel_id BIGINT NOT NULL REFERENCES hotels(id)` + índice. Filtrada por Hibernate `@TenantId`.
- Fechas-hora: `TIMESTAMPTZ` (Java `Instant`). Fechas de estancia: `DATE` (Java `LocalDate`) interpretadas en la zona
  del hotel. Horas de check-in/out: `TIME`.
- Dinero: `NUMERIC(12,2)` (Java `BigDecimal`). Porcentajes: `NUMERIC(5,2)` con `CHECK (x BETWEEN 0 AND 100)`.
- Auditoría de fila: `created_at`, `updated_at`, `created_by`, `updated_by`. Borrado lógico (`active`, `deleted_at`,
  `deleted_by`) en catálogos y usuarios; **nunca** en pagos (son libro contable).
- Enums como `VARCHAR` + `CHECK` (legible en SQL, mapeado con `@Enumerated(STRING)`).
- Unicidad de negocio siempre **por hotel** (`UNIQUE (hotel_id, …)`).
- Migraciones Flyway en `src/main/resources/db/migration`. Hibernate solo `validate`.

La columna **Fase** indica en qué migración aparece la tabla.

## Núcleo y seguridad (Fase 2–3)

| Tabla | Columnas clave | Restricciones / índices |
|---|---|---|
| `hotels` | `slug`, `name`, `timezone`, `currency`, `active`, `custom_domain` | `UNIQUE(slug)`, `slug ~ '^[a-z0-9-]{3,60}$'`, `UNIQUE(custom_domain)` |
| `permissions` | `code`, `module`, `action`, `description`, `reserved`, `sensitive`, `sort_order` | Global (sin `hotel_id`). `UNIQUE(code)` |
| `roles` | `hotel_id`, `code`, `name`, `description`, `system`, `active` | `UNIQUE(hotel_id, code)`; `hotel_id` NULL solo para `SUPER_ADMIN` |
| `role_permissions` | `role_id`, `permission_id` | PK compuesta |
| `users` | `hotel_id`, `email`, `password_hash`, `first_name`, `last_name`, `phone`, `active`, `must_change_password`, `failed_login_attempts`, `locked_until`, `last_login_at`, `permissions_version` | `UNIQUE(hotel_id, lower(email))`; `UNIQUE(lower(email)) WHERE hotel_id IS NULL` |
| `user_roles` | `user_id`, `role_id` | PK compuesta |
| `user_permission_overrides` | `user_id`, `permission_id`, `effect` (`GRANT`/`DENY`), `reason`, `granted_by` | `UNIQUE(user_id, permission_id)` |
| `refresh_tokens` | `user_id`, `token_hash`, `family_id`, `expires_at`, `revoked_at`, `replaced_by_id`, `ip`, `user_agent` | `UNIQUE(token_hash)` |
| `password_reset_tokens` | `user_id`, `token_hash`, `expires_at`, `used_at` | `UNIQUE(token_hash)` |
| `stored_files` | `hotel_id`, `storage_key`, `original_name`, `mime_type`, `size_bytes`, `sha256`, `width`, `height`, `owner_type`, `owner_id`, `uploaded_by`, `deleted_at` | `UNIQUE(storage_key)` |
| `audit_logs` | `hotel_id` (NULL = plataforma), `actor_user_id`, `actor_email`, `action`, `entity_type`, `entity_id`, `before_data JSONB`, `after_data JSONB`, `ip`, `user_agent`, `result` (`SUCCESS`/`DENIED`/`FAILED`), `created_at` | Índices `(hotel_id, created_at DESC)`, `(entity_type, entity_id)`. **Trigger que impide UPDATE/DELETE** |

## Configuración del hotel (Fase 5)

| Tabla | Columnas clave | Notas |
|---|---|---|
| `hotel_settings` | `check_in_time`, `check_out_time`, `reception_hours JSONB`, `min_booking_lead_hours`, `max_booking_lead_days`, `hold_minutes`, `allow_same_day_booking`, `max_rooms_per_booking`, `password_min_length`, `login_max_attempts`, `login_lock_minutes`, `rate_limits JSONB` | 1:1 con hotel. Límites con pisos mínimos validados en código |
| `hotel_branding` | `legal_name`, `rfc`, `description`, `address…`, `latitude`, `longitude`, `phones`, `whatsapp`, `contact_email`, `social JSONB`, `logo_file_id`, `logo_alt_file_id`, `favicon_file_id`, `hero_file_id`, `theme_light JSONB`, `theme_dark JSONB`, `default_mode`, `content JSONB` (textos del portal, banners, servicios destacados) | Colores validados `^#[0-9a-fA-F]{6}$` y contraste mínimo; nunca CSS libre |
| `hotel_policies` | `version`, `effective_from`, `free_cancel_hours`, `late_cancel_penalty_type` (`NONE`/`FIRST_NIGHT`/`PERCENT`/`FIXED`), `late_cancel_penalty_value`, `deposit_percent`, `pending_payment_expiry_minutes`, `no_show_policy`, `children_policy`, `pets_allowed`, `pets_policy`, `refund_policy`, `date_change_policy`, `privacy_notice`, `terms`, `created_by` | `UNIQUE(hotel_id, version)`. Inmutable: cada cambio = fila nueva |
| `email_settings` | `enabled`, `host`, `port`, `username`, `password_enc`, `starttls`, `from_address`, `from_name`, `reply_to`, `admin_recipients`, `bcc_recipients` | `password_enc` cifrado AES-GCM, nunca se devuelve |
| `payment_methods` | `code` (`CASH`/`CARD_TERMINAL`/`TRANSFER`/`ONLINE`), `enabled`, `instructions` | `UNIQUE(hotel_id, code)` |
| `payment_gateway_configs` | `provider`, `merchant_id`, `public_key`, `private_key_enc`, `webhook_secret_enc`, `production`, `enabled` | Credenciales cifradas |

## Inventario (Fase 5)

| Tabla | Columnas clave | Restricciones |
|---|---|---|
| `room_types` | `code`, `name`, `description`, `base_price`, `max_adults`, `max_children`, `max_occupancy`, `bed_count`, `bed_type`, `extra_adult_fee`, `extra_child_fee`, `included_occupancy`, `sort_order`, `active` | `UNIQUE(hotel_id, code)`, `base_price > 0`, capacidades `> 0` |
| `amenities` | `name`, `icon`, `active` | `UNIQUE(hotel_id, name)` |
| `room_type_amenities` / `room_amenities` | PK compuesta | |
| `rooms` | `room_type_id`, `number`, `name`, `floor`, `description`, `price_override`, `max_adults`, `max_children`, `operational_status`, `internal_notes`, `sort_order`, `active`, `version` | `UNIQUE(hotel_id, number)`; status CHECK |
| `room_images` | `room_id` o `room_type_id`, `file_id`, `sort_order`, `is_primary`, `alt_text` | `UNIQUE(room_id) WHERE is_primary` |
| `room_occupancies` | `room_id`, `stay DATERANGE`, `kind` (`RESERVATION`/`HOLD`/`BLOCK`), `reservation_id`, `maintenance_id`, `expires_at` | **`EXCLUDE USING gist (room_id WITH =, stay WITH &&)`**, `CHECK (NOT isempty(stay))`, índice gist `(hotel_id, stay)` |
| `maintenance_records` | `room_id`, `reason`, `starts_on`, `ends_on`, `status` (`OPEN`/`RELEASED`), `released_by`, `released_at`, `release_notes` | |
| `housekeeping_logs` | `room_id`, `from_status`, `to_status`, `notes`, `user_id`, `created_at` | |

## Tarifas y servicios (Fase 5)

| Tabla | Columnas clave | Notas |
|---|---|---|
| `seasons` | `name`, `kind` (`HIGH`/`LOW`/`HOLIDAY`), `starts_on`, `ends_on`, `active` | `ends_on >= starts_on` |
| `rate_rules` | `room_type_id` (NULL = todos), `rule_type` (`DATE_OVERRIDE`/`HOLIDAY`/`SEASON`/`WEEKEND`), `season_id`, `date_from`, `date_to`, `days_of_week`, `adjustment_type` (`FIXED_PRICE`/`PERCENT`/`AMOUNT`), `adjustment_value`, `min_nights`, `max_nights`, `priority`, `active` | Prioridad de resolución en [01 §C.4](01-ANALISIS-Y-ARQUITECTURA.md#c4-precios) |
| `promotions` | `code`, `name`, `discount_type` (`PERCENT`/`AMOUNT`), `discount_value`, `valid_from`, `valid_to`, `stay_from`, `stay_to`, `min_nights`, `room_type_ids`, `max_uses`, `used_count`, `stackable`, `active` | `UNIQUE(hotel_id, code)` |
| `tax_rates` | `name` (IVA, ISH), `percent`, `applies_to` (`ROOM`/`SERVICE`/`ALL`), `included_in_price`, `active` | |
| `extra_services` | `name`, `description`, `price`, `charge_mode` (`PER_STAY`/`PER_NIGHT`/`PER_PERSON`/`PER_PERSON_NIGHT`/`PER_UNIT`), `tax_rate_id`, `image_file_id`, `bookable_online`, `active` | |

## Clientes y reservaciones (Fase 6)

| Tabla | Columnas clave | Restricciones |
|---|---|---|
| `customers` | `user_id` (NULL = sin cuenta), `first_name`, `last_name`, `email`, `phone_country_code`, `phone`, `country`, `id_type`, `id_number_enc`, `preferences JSONB`, `notes`, `status`, `privacy_accepted_at` | Índices `(hotel_id, lower(email))`, `(hotel_id, phone)`; `UNIQUE(user_id)` |
| `reservations` | `folio`, `customer_id`, `contact_*` (copia al momento), `check_in`, `check_out`, `nights`, `adults`, `children`, `status`, `source`, `subtotal`, `discount_total`, `tax_total`, `services_total`, `total`, `deposit_required`, `amount_paid`, `balance` (calculado), `currency`, `policy_version_id`, `policy_snapshot JSONB`, `accepted_terms_at`, `special_requests`, `internal_notes`, `hold_expires_at`, `promotion_id`, `created_by`, `version` | `UNIQUE(hotel_id, folio)`, `check_out > check_in`, índices `(hotel_id, check_in)`, `(hotel_id, status)` |
| `reservation_rooms` | `reservation_id`, `room_id`, `room_type_id`, `adults`, `children`, `nightly_avg` | |
| `reservation_guests` | `reservation_id`, `full_name`, `is_primary`, `is_minor`, `id_type`, `id_number_enc`, `nationality` | |
| `reservation_price_lines` | `reservation_id`, `revision`, `reservation_room_id`, `line_type` (`NIGHT`/`EXTRA_GUEST`/`SERVICE`/`DISCOUNT`/`TAX`/`PENALTY`), `stay_date`, `description`, `rule_id`, `quantity`, `unit_amount`, `amount` | Histórico por `revision` |
| `reservation_services` | `reservation_id`, `extra_service_id`, `quantity`, `unit_price`, `tax_amount`, `total` | Precio congelado |
| `reservation_status_history` | `reservation_id`, `from_status`, `to_status`, `reason`, `user_id`, `created_at` | |
| `reservation_access_tokens` | `reservation_id`, `token_hash`, `expires_at`, `revoked_at` | Acceso del invitado |
| `stay_records` | `reservation_id`, `reservation_room_id`, `checked_in_at`, `checked_in_by`, `checked_out_at`, `checked_out_by`, `id_verified`, `notes`, `override_reason` | `UNIQUE(reservation_room_id)` → impide check-in duplicado |

## Pagos y caja (Fase 7)

| Tabla | Columnas clave | Restricciones |
|---|---|---|
| `payments` | `reservation_id`, `kind` (`CHARGE`/`DEPOSIT`/`REVERSAL`), `method`, `channel` (`FRONT_DESK`/`ONLINE`), `status` (`PENDING`/`COMPLETED`/`FAILED`/`CANCELLED`/`REVERSED`), `amount`, `currency`, `reference`, `card_last4`, `card_brand`, `gateway`, `gateway_charge_id`, `idempotency_key`, `cash_session_id`, `reverses_payment_id`, `reason`, `received_by`, `received_at` | `UNIQUE(hotel_id, idempotency_key)`, `UNIQUE(gateway, gateway_charge_id)`, `amount > 0`. **Sin DELETE** (trigger) |
| `payment_transactions` | `payment_id`, `gateway`, `gateway_event_id`, `event_type`, `status`, `raw_payload JSONB` (sin datos sensibles), `received_at`, `processed_at`, `error` | `UNIQUE(gateway, gateway_event_id)` → idempotencia de webhooks |
| `refunds` | `payment_id`, `amount`, `reason`, `policy_based`, `status`, `gateway_refund_id`, `requested_by`, `authorized_by`, `authorized_at` | Suma de reembolsos ≤ pago (validado en servicio y trigger) |
| `cash_sessions` | `user_id`, `opened_at`, `opening_float`, `closed_at`, `expected_cash`, `counted_cash`, `difference`, `status` (`OPEN`/`CLOSED`), `notes` | `UNIQUE(user_id) WHERE status='OPEN'` (patrón Citas) |
| `cash_movements` | `cash_session_id`, `type` (`PAYMENT`/`REFUND`/`WITHDRAWAL`/`DEPOSIT`/`ADJUSTMENT`), `method`, `amount`, `payment_id`, `reason`, `authorized_by` | Trigger impide modificar movimientos de sesiones cerradas |

## Notificaciones (Fase 6)

| Tabla | Columnas clave | Restricciones |
|---|---|---|
| `notification_templates` | `event_code`, `channel` (`EMAIL`), `subject`, `body_html`, `enabled`, `send_to_guest`, `send_to_admins` | `UNIQUE(hotel_id, event_code, channel)` |
| `notification_outbox` | `event_code`, `dedup_key`, `recipient`, `subject`, `body`, `status` (`PENDING`/`SENT`/`FAILED`/`CANCELLED`), `attempts`, `next_attempt_at`, `last_error`, `sent_at`, `related_type`, `related_id` | `UNIQUE(hotel_id, dedup_key, recipient)` |

## Tareas programadas

| Job | Frecuencia | Qué hace |
|---|---|---|
| Liberar retenciones | 1 min | Borra `room_occupancies` `HOLD` vencidas y marca `EXPIRED` las reservaciones `PENDING_PAYMENT` vencidas |
| No-show | 15 min | Aplica política de no presentación pasada la hora límite (zona del hotel) |
| Outbox | 30 s | Envía correos pendientes con reintentos y backoff |
| Recordatorios | 15 min | Encola recordatorios de llegada / salida según configuración |
| Purga | diaria | Tokens vencidos y archivos huérfanos marcados |
