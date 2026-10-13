# Nexora Hotel — Fase 1: Análisis y arquitectura

Documento de decisiones técnicas. Cubre los entregables **A–G** del prompt maestro:

- [A. Análisis comparativo de los proyectos de referencia](#a-análisis-comparativo-de-los-proyectos-de-referencia)
- [B. Tecnologías y versiones seleccionadas](#b-tecnologías-y-versiones-seleccionadas)
- [C. Arquitectura propuesta](#c-arquitectura-propuesta)
- [D. Modelo de datos](#d-modelo-de-datos) (detalle en [02-MODELO-DE-DATOS.md](02-MODELO-DE-DATOS.md))
- [E. Matriz de roles y permisos](#e-matriz-de-roles-y-permisos) (catálogo en [03-PERMISOS.md](03-PERMISOS.md))
- [F. Inventario de módulos](#f-inventario-de-módulos-y-funcionalidades)
- [G. Plan de implementación por fases](#g-plan-de-implementación-por-fases)

---

## A. Análisis comparativo de los proyectos de referencia

Se revisaron directamente los cuatro repositorios (código, `pom.xml`, `package.json`,
`application.properties`, scripts SQL, pruebas y Dockerfiles). Todos fueron accesibles.

| Aspecto | DemoPV (POS) | Citas (Hospital) | Decisión para Nexora Hotel |
|---|---|---|---|
| Java / Spring Boot | 17 / 3.2.5 | 17 / 3.2.5 | **17 / 3.2.5** (sin cambios) |
| Frontend | React 18.2 + Vite 5.2 + Tailwind 3.4 + axios + react-router 6.22 (JS) | Igual (JS) | **Igual**, JavaScript (no TS) para ser consistente |
| Organización backend | Capas planas, services sin interfaz, entidades en `model/` | `controller`, `dto/request`, `dto/response`, `entity`, `enums`, `mapper`, `service` + `service/impl`, `tenant`, `payment` | **Estructura de Citas** (interfaces + impl, mappers, DTO separados) |
| Multi-tenant | Manual: `TenantScope.scopeId(actor)` en cada query → fácil olvidarlo | Hibernate `@TenantId` + `TenantContext` (ThreadLocal) + `TenantGuard` `@PostLoad` + slug `/c/<slug>` + header `X-Hospital` | **Patrón Citas**, renombrado a `hotel_id` / `X-Hotel` / `/h/<slug>` |
| JWT | Access 30 min + refresh opaco en cookie httpOnly | Igual; refresh **hasheado SHA-256** en BD, rotación, revocación masiva | **Patrón Citas** |
| Fuerza bruta | Lockout por cuenta | Lockout por cuenta (5 intentos / 15 min) | Igual, parámetros administrables con piso mínimo |
| Roles/permisos | Rol por tienda + `Set<AppSection>` + `actionGrants` `"SECCION:ACCION"` (strings) | Roles fijos por enum + matchers por URL | **Nuevo**: tablas `permissions`/`role_permissions`/`user_roles`/`user_permission_overrides` (idea de DemoPV, normalizada) |
| Rate limiting | bucket4j en memoria por IP+endpoint | Igual, reglas fijas en código, desactivado en perfil test | **bucket4j** igual; reglas administrables por hotel con pisos mínimos en código |
| Validación | Bean Validation | Bean Validation + `ValidationPatterns` | Igual + validadores propios (RFC, CP, teléfono MX) |
| Excepciones | `GlobalExceptionHandler` | `GlobalExceptionHandler` + `ApiResponse{success,message,data}` + entry point/denied handler JSON | **Patrón Citas** + errores por campo (`errors: {campo: mensaje}`) para mostrarlos bajo cada input |
| Imágenes | `ImageStorageService` + **validación por magic bytes** | `FileStorageService` valida **solo la extensión** | **Patrón DemoPV** + tabla `stored_files` + re-codificación para quitar metadatos |
| Persistencia de uploads | `APP_UPLOADS_DIR` en volumen (Coolify) | Igual | Igual: `APP_STORAGE_DIR` absoluto, montado como volumen |
| Tema | 1 color → rampa 50–900 en CSS vars + sidebar derivado | Mismo mecanismo (copiado de DemoPV) | **Mismo mecanismo**, ampliado a la paleta completa del prompt + modo oscuro |
| Correo | SMTP en BD, contraseña cifrada **AES-256-GCM** (`EncryptedStringConverter`) | SMTP en BD, `@Async` | SMTP en BD cifrado (patrón DemoPV) + **outbox** con reintentos y deduplicación |
| Pagos | — | `PaymentGateway` (interfaz) + `OpenpayPaymentGateway` + webhook Basic auth | Interfaz igual; el webhook **re-consulta** el cargo en la pasarela antes de cambiar estado |
| Concurrencia | — | Lock pesimista + índice único parcial + `EXCLUDE USING gist` + `@Version` | **Mismo enfoque** con `daterange` sobre habitaciones |
| Migraciones | `ddl-auto=update` + `init.sql` | Scripts `db/NN_*.sql` manuales + `ddl-auto=validate` | **Flyway** (`V1__...sql`) + `ddl-auto=validate` |
| Pruebas | Ninguna | 11 clases de integración (TestRestTemplate + **H2**) | JUnit 5 + **Testcontainers PostgreSQL** (H2 no soporta `EXCLUDE`/`daterange`) |
| Swagger | No | No | **springdoc-openapi** (desactivado en prod) |
| Zona horaria | `LocalDateTime` + JVM en México | `LocalDateTime` | `TIMESTAMPTZ` + `Instant`; zona por hotel (`hotels.timezone`) |
| Deploy | Coolify + volumen | Dockerfile multi-stage, usuario no-root, volumen | **Dockerfile de Citas** |

### Debilidades encontradas que NO se replicarán

1. **DemoPV** — `ddl-auto=update` en producción y credenciales de BD escritas en `application.properties`.
2. **DemoPV** — aislamiento por tienda manual (`scopeId`): un repositorio que olvide el filtro filtra datos de otra tienda.
3. **Citas** — `FileStorageServiceImpl` acepta cualquier archivo con extensión `.jpg` (un ejecutable renombrado pasa).
4. **Citas** — el webhook de OpenPay cambia el estado del pago con base en el `type` del payload; si la credencial Basic se filtra, se pueden marcar pagos como cobrados. En Nexora Hotel el webhook solo **dispara** la verificación: el estado real se obtiene con `getCharge()` contra la pasarela.
5. **Citas** — llaves de sandbox de OpenPay y secreto JWT con valor por defecto en `application.properties`. En Nexora Hotel el perfil `prod` **falla al arrancar** si falta un secreto obligatorio.
6. **Citas** — correos armados por concatenación sin escapar HTML: un nombre de huésped con `<script>` viaja al correo. Aquí toda variable de plantilla se escapa.
7. **Ambos** — `LocalDateTime` sin zona: una política "48 h antes del check-in" se calcula mal si el servidor no está en la zona del hotel.
8. **Ambos** — sin bitácora de auditoría general (Citas solo historial de estados de cita).

### Qué se reutiliza conceptualmente (adaptado, no copiado a ciegas)

`TenantContext`, `TenantEntity`, `TenantGuard`, `TenantIdentifierResolver`, `HotelDirectory` (de `HospitalDirectory`),
`TenantJobs`, `JwtAuthFilter`, `JwtTokenProvider`, `RefreshTokenService`, `RestAuthenticationEntryPoint`,
`RestAccessDeniedHandler`, `RateLimiterService`/`RateLimitInterceptor`, `CredentialEncryptionService`,
`EncryptedStringConverter`, `ImageContentValidator`, `ApiResponse`, `PageResponse`, `PaymentGateway`;
en frontend `axios.js` (token en memoria + refresh de vuelo único), `tenant.js`, `router.jsx` (Link con slug),
`AuthContext`, `utils/theme.js`, `StaffLayout`, `PasswordInput`, `Modal`, `NotifyContext`.

---

## B. Tecnologías y versiones seleccionadas

### Backend (`nexora-hotel-backend`)

| Tecnología | Versión | Origen |
|---|---|---|
| Java | 17 | igual que referencias (JDK 17.0.12 instalado) |
| Spring Boot | 3.2.5 | igual que referencias |
| Spring Security / Data JPA / Validation / Mail | gestionadas por Boot 3.2.5 | igual |
| Hibernate | 6.4.x (gestionada) | igual; `@TenantId` requiere ≥ 6.0 |
| PostgreSQL driver | gestionada | igual; servidor PostgreSQL 13+ (local: 18) |
| jjwt | 0.11.5 | igual |
| bucket4j-core | 8.10.1 | igual |
| Lombok | gestionada | igual |
| jackson-datatype-hibernate6 | gestionada | igual |
| OpenPDF | 2.0.3 | DemoPV (comprobantes y reportes PDF) |
| Apache POI (poi-ooxml) | 5.3.0 | DemoPV (versión más reciente de las dos) |
| ZXing | 3.5.3 | ambos (QR en comprobante) |
| **Flyway** | gestionada por Boot 3.2.5 (9.22.x) | nueva — exigida por el prompt |
| **springdoc-openapi-starter-webmvc-ui** | 2.5.0 | nueva — línea compatible con Boot 3.2 |
| **Testcontainers** (postgresql, junit-jupiter) | gestionada por Boot 3.2.5 (1.19.x) | nueva — Docker disponible localmente |
| Maven | Wrapper 3.9.x | `mvnw` (Maven no está en el PATH) |

Se elige **OpenPDF** sobre PDFBox (Citas) por ser de más alto nivel para tablas y comprobantes; se elige
**POI 5.3.0** (DemoPV) sobre 5.2.5 (Citas) por ser la más reciente ya probada en tus proyectos.

### Frontend (`nexora-hotel-frontend`)

| Tecnología | Versión | Origen |
|---|---|---|
| React / React DOM | ^18.2.0 | igual |
| Vite / @vitejs/plugin-react | ^5.2.0 / ^4.2.1 | igual |
| react-router-dom | ^6.22.3 | igual |
| axios | ^1.6.8 | igual |
| Tailwind CSS / PostCSS / Autoprefixer | ^3.4.1 / ^8.4.38 / ^10.4.19 | igual |
| recharts | ^2.12.2 | DemoPV (dashboard) |
| **Vitest + @testing-library/react + jsdom** | ^1.6 / ^14 / ^24 | nuevo — el prompt exige pruebas de frontend; Vitest 1.x es la línea compatible con Vite 5 |

No se agrega librería de formularios: tus proyectos validan a mano; se centraliza en `src/utils/validators.js`
reflejando exactamente las reglas del backend.

---

## C. Arquitectura propuesta

### C.1 Multi-hotel (multi-tenant) — patrón Citas

```
                       ┌──────────────────────────── Request ────────────────────────────┐
 Frontend /h/<slug>/…  │ Authorization: Bearer <jwt>      X-Hotel: <slug>                │
                       └─────────────────────────────────┬───────────────────────────────┘
                                                         ▼
                                              JwtAuthFilter
                         ┌─ con sesión  → TenantContext = user.hotelId (manda sobre el header)
                         ├─ sin sesión  → TenantContext = hotel activo del slug (404 si no existe)
                         └─ nada        → TenantContext = NONE (no ve datos)
                                                         ▼
                Hibernate @TenantId filtra TODA consulta por hotel_id y lo llena al insertar
                TenantGuard @PostLoad corta findById / relaciones LAZY de otro hotel (→ 404)
```

- **SUPER_ADMIN** (Nexora) vive en la raíz (`/`), sin hotel: da de alta hoteles y su primer ADMIN. Opera en `ROOT`.
- Cada hotel tiene **sus propios** admins, recepcionistas, huéspedes, roles, habitaciones, tarifas, configuración y archivos.
- El mismo correo puede existir en dos hoteles (unicidad `(hotel_id, email)`).
- Tareas programadas recorren hotel por hotel (`TenantJobs`), cada uno en su transacción.
- Archivos bajo `hotels/{hotelId}/…` en el almacenamiento.
- Evolución futura: dominio propio por hotel (`hotels.custom_domain` resuelto por `Host`), sin cambiar el modelo.

### C.2 Capas del backend (paquete `com.nexora.hotel`)

```
config/         Seguridad, CORS, Jackson, async, OpenAPI, almacenamiento, propiedades tipadas
security/       JwtAuthFilter, JwtTokenProvider, PermissionEvaluator (@perm), cifrado, rate limit
tenant/         TenantContext, TenantEntity, TenantGuard, TenantIdentifierResolver, HotelDirectory, TenantJobs
controller/     REST. Sin lógica: valida (@Valid), autoriza (@PreAuthorize) y delega
  publicapi/    Endpoints públicos del portal (catálogo, disponibilidad, reservación de invitado)
dto/request     Entradas validadas con Bean Validation
dto/response    Salidas (nunca se exponen entidades)
mapper/         Entidad ↔ DTO (manual, como en Citas)
service/        Interfaces
service/impl/   Reglas de negocio, transacciones
domain/         Lógica pura sin Spring: motor de precios, máquina de estados, cálculo de políticas
entity/         JPA (todas las de negocio extienden TenantEntity / AuditableEntity)
enums/
repository/     Spring Data JPA + Specifications para filtros dinámicos
validation/     Anotaciones y validadores (@MxPhone, @Rfc, @PostalCodeMx, @HexColor, @DateRange…)
exception/      BusinessException, NotFound, Conflict, GlobalExceptionHandler
storage/        StorageService (interfaz) + LocalFileSystemStorage (+ S3 en el futuro)
payment/        PaymentGateway (interfaz) + adaptadores + verificación de webhooks
notification/   Outbox, plantillas, envío SMTP
audit/          AuditService + @Audited
scheduler/      Expiración de retenciones, no-shows, recordatorios, purga de tokens
util/
```

**Lógica de dominio pura** (`domain/`): el cálculo de precio por noche, la máquina de estados de la reservación y
la evaluación de la política de cancelación no dependen de Spring ni de la BD, así se prueban con tests unitarios rápidos.

### C.3 Motor de disponibilidad y anti doble-reservación

Tabla única **`room_occupancies`** que representa todo lo que ocupa una habitación en un rango de fechas:
reservaciones activas, retenciones temporales (checkout en curso) y bloqueos de mantenimiento.

```sql
stay daterange NOT NULL  -- [check_in, check_out)  → la fecha de salida NO es una noche
EXCLUDE USING gist (room_id WITH =, stay WITH &&)
```

1. **Consulta**: habitaciones activas, capacidad suficiente, estado operativo distinto de *fuera de servicio*, sin fila en
   `room_occupancies` que traslape `[entrada, salida)`.
2. **Retención**: al iniciar el pago se inserta una ocupación `HOLD` con `expires_at`. Un job libera las vencidas; además,
   antes de insertar se borran las vencidas de esa habitación dentro de la misma transacción.
3. **Confirmación**: `SELECT … FOR UPDATE` sobre las habitaciones (orden por id, evita deadlocks), se revalida, y la
   restricción `EXCLUDE` es la garantía final: si dos transacciones llegan a la vez, PostgreSQL rechaza la segunda
   (`SQLSTATE 23P01`) y se responde **409** "La habitación ya no está disponible para esas fechas".
4. `@Version` en `reservations` contra ediciones concurrentes (patrón Citas).

Estado operativo (`AVAILABLE/OCCUPIED/CLEANING/MAINTENANCE/OUT_OF_SERVICE`) y disponibilidad por fechas son conceptos
separados: el primero es "cómo está hoy la habitación", el segundo sale de `room_occupancies`.

### C.4 Precios

Por noche se elige **una** regla de tarifa (mayor prioridad gana):
`DATE_OVERRIDE (fecha específica) > HOLIDAY > SEASON > WEEKEND > precio base del tipo`;
empate → campo `priority` numérico → la más reciente. Luego: cargos por huésped adicional, promoción (no acumulables
salvo bandera `stackable`), servicios, impuestos. Todo en `BigDecimal` con `RoundingMode.HALF_UP` a 2 decimales al
final de cada línea. El desglose completo se guarda en `reservation_price_lines` y **nunca se recalcula** una
reservación confirmada salvo un cambio explícito (fechas/habitación/huéspedes), que genera nueva versión del desglose
y queda en auditoría.

### C.5 Políticas sin retroactividad

`hotel_policies` es versionada (fila nueva por cada cambio). Cada reservación guarda `policy_version_id` **y** un
`policy_snapshot JSONB` de lo aceptado. La cancelación se evalúa con el snapshot, en la **zona horaria del hotel**,
contra la hora exacta del check-in (`fecha entrada + hora check-in`). Resultado: `FREE`, `WITH_PENALTY(monto)`,
`REQUIRES_ADMIN`.

### C.6 Máquina de estados de la reservación

```
PENDING ──────────────┬──► CONFIRMED ──► IN_HOUSE ──► CHECKED_OUT
   │                  │        │            
   ▼                  │        ├──► NO_SHOW
PENDING_PAYMENT ──────┘        └──► CANCELLED
   │   └──► EXPIRED (retención vencida sin pago)
   └──► CANCELLED
```

Transiciones fuera del mapa → `BusinessException`. Cada transición escribe `reservation_status_history`.
Los *overrides* (p. ej. check-in con saldo pendiente) requieren el permiso `RESERVATIONS:OVERRIDE` y motivo obligatorio.

### C.7 Pagos

- `payments` es un **libro**: nunca se borra; correcciones = `refunds` o movimiento de reverso con motivo y autorización.
- Pagos en línea: `PaymentGateway` (interfaz). La pasarela concreta (Openpay ya está integrada en Citas y opera en
  México) se conecta en la Fase 7. **Sin pasarela configurada el hotel solo acepta pago en recepción**: no hay cobro simulado.
- Webhook: autenticado, guarda el evento crudo en `payment_transactions` con `UNIQUE(gateway, gateway_event_id)`
  (idempotencia), re-consulta el cargo y solo entonces cambia el estado.
- `Idempotency-Key` obligatorio en los endpoints que crean cobros.
- Nunca se guarda número de tarjeta ni CVV: el frontend tokeniza con el SDK de la pasarela.

### C.8 Seguridad

- Access JWT 15–30 min en **memoria** del frontend; refresh opaco en cookie `httpOnly`, `SameSite`, `Secure` en prod,
  ruta `/api/auth`, hasheado en BD, rotado en cada uso. Detección de reutilización: si llega un refresh ya rotado se
  revocan todas las sesiones del usuario.
- CSRF: la API es stateless con Bearer; la única cookie es la de refresh, limitada a `/api/auth` con `SameSite=Lax`
  (o `None`+`Secure` cross-site) y el endpoint exige además el header `X-Requested-With`.
- Permisos efectivos cargados por request desde una caché en memoria invalidada al cambiar roles/permisos
  (sin redeploy, sin esperar a que expire el JWT).
- Anti-escalación: solo se pueden otorgar permisos que uno mismo tiene; el rol de sistema ADMIN no puede perder los
  permisos de seguridad; no se puede desactivar/degradar al **último admin activo** del hotel.
- Huésped invitado: folio aleatorio no secuencial (`NXH-7K3Q9F`) + token de acceso de 256 bits (guardado hasheado)
  en el enlace del correo. Folio solo no basta.
- Logs sin contraseñas, tokens ni datos de tarjeta (filtro de enmascarado).
- Auditoría inmutable: trigger en PostgreSQL que impide `UPDATE`/`DELETE` sobre `audit_logs`.

### C.9 Archivos persistentes

`StorageService` con implementación de disco local en `APP_STORAGE_DIR` (ruta **absoluta** fuera del artefacto,
montada como volumen). Claves generadas por el servidor (`hotels/{id}/rooms/{uuid}.jpg`), verificación de que la ruta
normalizada queda dentro de la raíz, tipo real por magic bytes, re-codificación de JPEG/PNG (elimina EXIF/GPS), límite
de tamaño, registro en `stored_files`. Se sirven por `GET /media/{key}` con caché HTTP. El arranque en `prod` falla si
`APP_STORAGE_DIR` es relativo o no escribible.

### C.10 Notificaciones (outbox)

La operación de negocio inserta en `notification_outbox` dentro de **su misma transacción** con
`dedup_key` único (p. ej. `RESERVATION_CONFIRMED:{reservationId}`). Un job envía con el SMTP del hotel, reintenta con
backoff y registra el resultado. Así un reintento de la operación no duplica correos y un correo no sale si la
operación hizo rollback.

### C.11 Frontend

```
src/
  api/            axios.js (token en memoria, refresh de vuelo único, X-Hotel), un módulo por recurso
  config/         permissions.js (espejo del catálogo), navigation.js (menú declarativo por permiso)
  context/        AuthContext, HotelConfigContext (tema + identidad), NotifyContext
  guards/         RequireAuth, RequirePermission, RequireGuest
  hooks/          usePermission, usePolling, useDebouncedValue, useEscapeKey
  layouts/        PublicLayout, StaffLayout (admin y recepción comparten shell), PlatformLayout, GuestLayout
  components/     ui/ (Button, Input, Select, Modal, Table, Pagination, Badge, EmptyState, Spinner,
                  ConfirmDialog, PasswordInput+medidor), DateRangePicker, PriceBreakdown, ImageUploader…
  pages/          public/, guest/, admin/, reception/, platform/
  utils/          validators.js, money.js, dates.js, theme.js, media.js
  tenant.js, router.jsx
```

`<Can permission="ROOMS:CREATE">` oculta botones; `RequirePermission` protege rutas; el menú se construye filtrando
`navigation.js` contra los permisos del usuario. **El backend es la única fuente de verdad de autorización.**

---

## D. Modelo de datos

Resumen de relaciones principales (todas las tablas de negocio llevan `hotel_id` FK + índice):

```
hotels 1─┬─1 hotel_settings          (operación, horarios, zona, moneda, límites de seguridad)
         ├─1 hotel_branding          (identidad, logos, tema claro/oscuro, redes, contenido)
         ├─N hotel_policies          (versionada)
         ├─1 email_settings          (SMTP cifrado)
         ├─N payment_gateway_configs (credenciales cifradas)
         ├─N users ─N─ user_roles ─N─ roles ─N─ role_permissions ─N─ permissions (catálogo global)
         │        └─N user_permission_overrides ─N─ permissions
         ├─N customers ─0..1─ users   (huésped registrado)
         ├─N room_types ─N rooms ─N room_images ─1 stored_files
         │                    └─N room_amenities ─N amenities
         ├─N room_occupancies         (reservación | retención | bloqueo; EXCLUDE gist)
         ├─N maintenance_records, housekeeping_logs
         ├─N rate_rules, seasons, promotions, extra_services
         ├─N reservations ─┬─N reservation_rooms ─1 rooms
         │                 ├─N reservation_guests
         │                 ├─N reservation_price_lines
         │                 ├─N reservation_services ─1 extra_services
         │                 ├─N reservation_status_history
         │                 ├─N stay_records (check-in/out)
         │                 └─N payments ─N payment_transactions, ─N refunds
         ├─N cash_sessions ─N cash_movements ─0..1 payments
         ├─N notification_templates, notification_outbox
         ├─N stored_files
         └─N audit_logs
refresh_tokens, password_reset_tokens, reservation_access_tokens → users / reservations
```

Ajustes respecto a la lista conceptual del prompt (sin tablas redundantes):

| Propuesta del prompt | Decisión | Motivo |
|---|---|---|
| `room_blocks` + bloqueo en reservaciones | `room_occupancies` única | Una sola restricción `EXCLUDE` cubre reservas, retenciones y bloqueos |
| `cancellation_policies` | `hotel_policies` versionada | Cancelación es una de muchas políticas; todas se versionan juntas |
| `pricing_rules` | `rate_rules` (+ `seasons`, `promotions`) | Mismo concepto, nombre del dominio hotelero |
| `services` / `reservation_services` | `extra_services` / `reservation_services` | Evita choque con la capa "service" del código |
| `reservation_price_details` | `reservation_price_lines` | Una fila por noche/cargo/impuesto |
| `checkin_checkout_records` | `stay_records` | Un registro por estancia con hora real de entrada y salida |
| `cash_registers` | `cash_sessions` | Lo que se abre y cierra es la sesión (turno) de caja |
| `notification_logs` | `notification_outbox` | Es a la vez cola y bitácora de envíos |
| — | `user_permission_overrides`, `hotel_branding`, `payment_gateway_configs`, `reservation_access_tokens`, `housekeeping_logs` | Requeridos por los puntos 5, 15, 12, 4 y 23 del prompt |

Detalle de columnas, índices y restricciones: [02-MODELO-DE-DATOS.md](02-MODELO-DE-DATOS.md).

---

## E. Matriz de roles y permisos

Roles de sistema por hotel (sembrados al crear el hotel, no se renombran ni eliminan; sus permisos sí se editan,
excepto los reservados):

| Rol | Ámbito | Descripción |
|---|---|---|
| `SUPER_ADMIN` | Plataforma (sin hotel) | Nexora: alta/baja de hoteles y su primer admin. No opera hoteles. |
| `ADMIN` | Hotel | Todo el hotel. Siempre conserva los permisos reservados. |
| `RECEPTIONIST` | Hotel | Operación diaria; permisos ajustables. |
| `GUEST` | Hotel | Huésped registrado; solo sus propios datos (no usa el catálogo de staff). |

Además el ADMIN puede crear **roles personalizados** (p. ej. "Ama de llaves", "Mantenimiento") combinando permisos.

Matriz por defecto (resumen; catálogo completo en [03-PERMISOS.md](03-PERMISOS.md)):

| Módulo | ADMIN | RECEPTIONIST (default) |
|---|---|---|
| Dashboard operativo | ✔ | ✔ |
| Reservaciones: ver / crear / editar / confirmar / cancelar / check-in / check-out / reenviar | ✔ | ✔ |
| Reservaciones: excepciones de política (override) / aplicar descuento | ✔ | ✘ |
| Disponibilidad | ✔ | ✔ |
| Habitaciones: ver | ✔ | ✔ |
| Habitaciones: crear / editar / desactivar | ✔ | ✘ |
| Habitaciones: bloquear por mantenimiento / cambiar estado de limpieza | ✔ | ✘ / ✔ |
| Clientes: ver / crear / editar | ✔ | ✔ |
| Clientes: datos sensibles / exportar | ✔ | ✘ |
| Pagos: ver / registrar / generar enlace | ✔ | ✔ |
| Pagos: reverso / reembolso / reembolso extraordinario | ✔ | ✘ |
| Caja: abrir / cerrar la propia | ✔ | ✔ |
| Caja: ver todas / ajustes | ✔ | ✘ |
| Tarifas, temporadas, promociones, servicios | ✔ | ver |
| Reportes operativos | ✔ | ✔ |
| Reportes financieros / exportar | ✔ | ✘ |
| Usuarios, roles y permisos | ✔ | ✘ |
| Configuración, tema, contenido, políticas, plantillas | ✔ | ✘ |
| Correo y pasarela de pago (credenciales) | ✔ (reservado) | ✘ (no asignable) |
| Bitácora de auditoría | ✔ | ✘ |

**Reservados** (no asignables a ningún rol distinto de ADMIN): `ROLES:MANAGE`, `EMAIL_SETTINGS:MANAGE`,
`PAYMENT_SETTINGS:MANAGE`, `AUDIT:VIEW`. Es la "operación reservada obligatoriamente al sistema" del prompt.

---

## F. Inventario de módulos y funcionalidades

| # | Módulo | Backend | Frontend | Fase |
|---|---|---|---|---|
| 1 | Plataforma (SUPER_ADMIN) | Alta/baja de hoteles, primer admin, slug | Panel de plataforma | 3 |
| 2 | Autenticación | Login, refresh, logout, registro huésped, recuperación, cambio forzado, lockout | Login, registro, recuperar/restablecer, medidor de fuerza | 3 |
| 3 | Usuarios | CRUD staff, activar/desactivar, último admin protegido | Administración → Usuarios | 3 |
| 4 | Roles y permisos | Catálogo, roles personalizados, overrides por usuario, invalidación de caché | Matriz editable módulo × acción | 3 |
| 5 | Auditoría | `@Audited`, consulta filtrada, inmutable | Bitácora con filtros | 3 |
| 6 | Configuración del hotel | Identidad, horarios, zona, moneda, parámetros, límites | Formularios por pestañas | 5 |
| 7 | Tema e identidad | Paleta completa, logos, favicon, contraste validado | Editor con vista previa | 4–5 |
| 8 | Archivos | Subida segura, `stored_files`, `/media` | `ImageUploader` con orden | 5 |
| 9 | Tipos de habitación y amenidades | CRUD | CRUD | 5 |
| 10 | Habitaciones | CRUD, fotos, estado operativo, calendario | Inventario + calendario | 5 |
| 11 | Mantenimiento y limpieza | Bloqueos, historial, liberación autorizada | Tablero de limpieza | 7 |
| 12 | Tarifas | Reglas, temporadas, festivos, promociones, estancia mín/máx | Editor de tarifas | 5 |
| 13 | Servicios adicionales | CRUD, forma de cobro | CRUD | 5 |
| 14 | Políticas | Versionadas, evaluador de cancelación | Formulario | 5 |
| 15 | Disponibilidad | Motor + retención | Buscador | 6 |
| 16 | Reservaciones | Crear/editar/confirmar/cancelar, máquina de estados, historial, PDF | Recepción y admin | 6 |
| 17 | Notificaciones | Outbox, plantillas, SMTP por hotel | Plantillas, configuración SMTP | 6 |
| 18 | Pagos | Libro, parciales, anticipos, reembolsos, idempotencia | Cobro, historial | 7 |
| 19 | Pasarela en línea | Adaptador + webhook verificado | Tokenización SDK | 7 |
| 20 | Caja | Apertura, movimientos, corte, cierre inmutable | Caja de recepción | 7 |
| 21 | Check-in / check-out | Registro de huéspedes, saldo, habitación a limpieza | Flujos guiados | 7 |
| 22 | Portal público | Catálogo, galería, contacto, políticas | Sitio completo | 8 |
| 23 | Reservación pública | Flujo de 10 pasos, acceso por token | Wizard móvil | 8 |
| 24 | Cuenta del huésped | Perfil, historial, comprobantes, solicitud ARCO | Mis reservaciones | 8 |
| 25 | Clientes | CRUD, historial, privacidad por permiso | Clientes | 6 |
| 26 | Dashboard y reportes | Métricas definidas (cobrado vs devengado), exportación | Dashboard, reportes | 9 |

---

## G. Plan de implementación por fases

Cada fase termina **compilando, con pruebas pasando y verificada en ejecución**.

| Fase | Entregable verificable |
|---|---|
| **1. Análisis y arquitectura** | Este documento + modelo de datos + catálogo de permisos |
| **2. Base backend** | Proyecto Maven, Flyway V1 (núcleo: hoteles, usuarios, roles, permisos, archivos, auditoría), multi-tenant, `ApiResponse`, errores, validación, logging con enmascarado, Swagger, perfiles `dev/test/prod`, Testcontainers. **Arranca contra PostgreSQL** y `/actuator/health` responde. |
| **3. Seguridad** | Login/refresh/logout, recuperación, lockout, permisos dinámicos con caché, anti-escalación, último admin, rate limiting, auditoría de seguridad, SUPER_ADMIN y alta de hoteles. Tests de la sección 29 (seguridad). |
| **4. Base frontend** | Proyecto Vite, tema dinámico, layouts público/staff/plataforma, auth, guards, menú por permisos, pantallas de usuarios y roles conectadas. |
| **5. Administración hotelera** | Configuración, tema, archivos, tipos, habitaciones, amenidades, servicios, políticas, tarifas. |
| **6. Reservaciones** | Motor de disponibilidad, reservación por recepción y pública, estados, cancelación con políticas, historial, correos (outbox). Tests de concurrencia reales. |
| **7. Pagos y operación** | Pagos en recepción y parciales, pasarela + webhook, reembolsos, caja, check-in/out, limpieza y mantenimiento. |
| **8. Portal público** | Inicio, galería, habitaciones, buscador, flujo de reservación, registro, mis reservaciones. |
| **9. Reportes** | Dashboard, ocupación, ingresos, pagos, cancelaciones, exportación Excel/CSV/PDF. |
| **10. Calidad y entrega** | Revisión de seguridad, rendimiento, responsividad, documentación, Docker, manual de despliegue/operación. |

### Decisiones que requieren tu confirmación (no bloquean la Fase 2)

1. **Pasarela**: propongo reutilizar Openpay (ya integrada y operando en México en Citas) como primer adaptador en la Fase 7.
2. **Dominio por hotel**: arranco con `/h/<slug>` como Citas; dominio propio queda preparado en el modelo.
3. **Facturación CFDI**: fuera de alcance; se guardan RFC y razón social para integrarla después.
