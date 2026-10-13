# Nexora Hotel — Backend

API REST multi-hotel para administración hotelera y reservaciones. Cada hotel tiene sus propios
administradores, recepcionistas, huéspedes, habitaciones, tarifas y configuración, y **no puede ver datos de otro hotel**.

- Arquitectura y decisiones: [docs/01-ANALISIS-Y-ARQUITECTURA.md](docs/01-ANALISIS-Y-ARQUITECTURA.md)
- Modelo de datos: [docs/02-MODELO-DE-DATOS.md](docs/02-MODELO-DE-DATOS.md)
- Catálogo de permisos: [docs/03-PERMISOS.md](docs/03-PERMISOS.md)
- Reportes y qué significa cada cifra: [docs/04-REPORTES.md](docs/04-REPORTES.md)
- Calidad, seguridad y despliegue paso a paso: [docs/05-CALIDAD-Y-DESPLIEGUE.md](docs/05-CALIDAD-Y-DESPLIEGUE.md)

## Estado

| Fase | Estado |
|---|---|
| 1. Análisis y arquitectura | ✅ |
| 2. Base backend (Flyway, multi-hotel, errores, validación, logging, Swagger, perfiles, Testcontainers) | ✅ |
| 3. Seguridad (login, JWT + refresh rotado, recuperación, bloqueo, permisos dinámicos, anti-escalación, último admin, rate limiting, auditoría, plataforma SUPER_ADMIN) | ✅ |
| 4. Base frontend (layouts, auth, guards, menú por permisos, usuarios, roles, auditoría, plataforma) | ✅ |
| 5. Administración hotelera (configuración, tema, imágenes persistentes, tipos, habitaciones, amenidades, bloqueos sin traslape, limpieza, políticas versionadas, tarifas, impuestos, servicios, simulador de precios) | ✅ |
| 6. Reservaciones (motor de disponibilidad sin doble reserva, alta en recepción y en línea, folio + enlace seguro, confirmación, expiración automática, cancelación con política aceptada, modificación con recotización, calendario, clientes, correo SMTP por hotel con plantillas y bitácora, comprobante PDF) | ✅ |
| 7. Pagos y operación (cobros en recepción con idempotencia, pagos parciales y anticipos, Openpay con página de pago alojada y verificación directa con la pasarela, webhooks autenticados e idempotentes, reembolsos dentro y fuera de política, reversos, cargos a la cuenta, caja con corte y cierre inmutable, check-in con registro de huéspedes, check-out con salida anticipada, no-show, tablero de limpieza) | ✅ |
| 8. Portal público (galería, ficha de habitación, políticas, elección de forma de pago, aviso si cambia la disponibilidad; cuentas de huésped con correo verificado, mis reservaciones, pagos, comprobantes, perfil, exportar datos y solicitudes ARCO con anonimización) | ✅ |
| 9. Reportes (dashboard con habitaciones, operación del día y dinero; 11 reportes operativos y financieros que separan cobrado, anticipos, devengado, reservado y por cobrar; exportación CSV, Excel y PDF auditada) | ✅ |
| 10. Calidad y entrega (cobertura automática de permisos en todos los endpoints, revisión de seguridad, índices para reportes, carga diferida en el frontend, verificación responsiva a 360/390 px, prueba de archivos persistentes, guía de despliegue) | ✅ |

Endpoints: autenticación (`/api/auth/**`), plataforma (`/api/platform/hotels`), administración del hotel (`/api/admin/**`: usuarios, roles, auditoría, configuración, habitaciones, tarifas, servicios), reservaciones, estancia y cobros (`/api/admin/reservations/**`, `/customers`, `/payments`, `/cash`, `/payment-settings`), correo (`/api/admin/email-settings`, `/notification-templates`), cuenta del huésped (`/api/account/**`, `/api/public/account/**`), solicitudes de datos (`/api/admin/data-requests`), dashboard y reportes (`/api/admin/dashboard`, `/api/admin/reports/**`), público (`/api/public/hotel`, `/room-types`, `/services`, `/policies`, `/availability`, `/reservations`, `/payment-options`, `/payments/{ref}`, `/webhooks/openpay/{slug}`) e imágenes (`/media/**`). Ver Swagger en dev.

## Requisitos

- JDK 17
- PostgreSQL 13+ (probado con 16 y 18). La extensión `btree_gist` se crea sola (es "trusted").
- Docker (solo para correr las pruebas de integración)
- Maven no es necesario: usa `./mvnw`

## Arranque local

```bash
# 1. Base de datos
psql -U postgres -c "CREATE DATABASE nexora_hotel ENCODING 'UTF8' TEMPLATE template0;"

# 2. Variables: copia y llena (DB_PASSWORD y dos secretos aleatorios distintos)
cp .env.example .env
openssl rand -base64 48   # APP_JWT_SECRET
openssl rand -base64 48   # APP_ENCRYPTION_KEY

# 3. Arrancar (perfil dev; Flyway crea el esquema)
./mvnw spring-boot:run
```

- API: http://localhost:8090
- Swagger (solo dev): http://localhost:8090/swagger-ui.html
- Salud: http://localhost:8090/actuator/health

## Pruebas

```bash
./mvnw test
```

Corre unitarias (`*Test`) e integración (`*IT`) contra **PostgreSQL real en Testcontainers** (no H2: el sistema usa
`daterange`, `EXCLUDE USING gist`, JSONB y triggers). Requiere Docker encendido.

## Perfiles y variables

| Perfil | Uso |
|---|---|
| `dev` (por defecto) | Local. Lee `.env`. Swagger activo. |
| `test` | Pruebas automatizadas. |
| `prod` | Producción. Swagger apagado. **No arranca** si un secreto es débil o igual a otro, si la cookie no es `Secure`, si `APP_STORAGE_DIR` no es absoluto o si CORS incluye orígenes locales o http. |

Todas las variables están documentadas en [.env.example](.env.example). Los secretos **solo** van en variables de
entorno; la configuración funcional del hotel (tema, políticas, tarifas, SMTP…) vive en PostgreSQL.

## Estructura

```
src/main/java/com/nexora/hotel
  config/       Seguridad, CORS, propiedades tipadas, auditoría JPA, OpenAPI, reloj, validación de producción
  tenant/       Multi-hotel: TenantContext, TenantEntity (@TenantId), TenantGuard, TenantFilter, HotelDirectory
  controller/   REST (publicapi/ = portal sin sesión)
  dto/          ApiResponse, PageResponse, request/, response/
  entity/ enums/ repository/
  exception/    GlobalExceptionHandler (traduce también violaciones de PostgreSQL)
  security/     AuthPrincipal, respuestas 401/403
  validation/   @MxPhone, @PersonName, @Rfc, @MxPostalCode, @HexColor
  web/          RequestIdFilter
src/main/resources/db/migration   Migraciones Flyway (nunca editar una ya aplicada)
```

## Multi-hotel

- Personal y huéspedes entran por `/h/<slug>` en el frontend, que manda `X-Hotel: <slug>` en cada request.
- Con sesión, el hotel es **siempre el del usuario**: el header se ignora.
- Hibernate filtra toda consulta por `hotel_id`; `TenantGuard` bloquea cargas por id de otro hotel (responde 404).
- El SUPER_ADMIN de Nexora no pertenece a ningún hotel y solo administra hoteles.

## Despliegue (Coolify)

1. Construir con el `Dockerfile` (multi-stage, usuario sin privilegios, healthcheck).
2. Montar un **volumen persistente** en `/data/storage`. Sin él, las imágenes se pierden en cada redeploy.
3. Una sola instancia (el límite de solicitudes vive en memoria).
4. Definir las variables de `.env.example` con `SPRING_PROFILES_ACTIVE=prod`, `APP_COOKIE_SECURE=true` y, si
   frontend y backend están en dominios distintos, `APP_COOKIE_SAMESITE=None`.

Lista de verificación completa en [docs/05-CALIDAD-Y-DESPLIEGUE.md](docs/05-CALIDAD-Y-DESPLIEGUE.md).

## Reglas del proyecto

- Dinero siempre `BigDecimal` / `NUMERIC(12,2)`; nunca `float`/`double`.
- Fechas-hora en `TIMESTAMPTZ` (`Instant`); reglas horarias en la zona del hotel. La JVM corre siempre en UTC (ver `NexoraHotelApplication`): si no, las columnas `TIME` se leen corridas.
- Nunca registrar en logs contraseñas, tokens, credenciales ni datos de tarjeta.
- Pagos y auditoría son de solo inserción; las correcciones se hacen con movimientos trazables.

## Pago en línea (Openpay, modo pruebas)

1. Crea una cuenta de pruebas en el [panel sandbox de Openpay](https://sandbox-dashboard.openpay.mx) y copia el **ID de comercio** y la **llave privada** (`sk_…`).
2. En el panel del hotel: **Configuración → Pagos**, captura ambos datos, deja activado *Modo pruebas*, activa *Cobrar en línea* y usa **Probar conexión**.
3. (Opcional, requiere que la API sea accesible desde internet) **Generar credenciales del webhook** y regístralas en Openpay → Webhooks con autenticación básica.
   Sin webhook el sistema funciona igual: verifica el pago al volver el huésped y cada 2 minutos concilia los pagos pendientes.
4. Con una política de anticipo mayor a 0 %, las reservaciones del portal quedan *pendientes de pago* y se confirman solas cuando Openpay confirma el cargo.

Un pago en línea **solo** se marca como cobrado tras consultar el cargo a Openpay con la llave privada; el regreso del navegador o el cuerpo de un webhook nunca bastan. Las pruebas automatizadas usan un simulador HTTP local de la API de Openpay (`OpenpayStub`), no cobros simulados en el código de producción.

## Idiomas (ES | EN)

La aplicación envía `Accept-Language: es` o `en` en cada petición y el servidor responde en ese idioma: errores,
validaciones, mensajes de éxito, avisos (check-in), desglose de precio, reportes, exportaciones y comprobante PDF.
**Sin encabezado responde en español** (correos programados, webhooks, integraciones).

- Diccionario: `src/main/resources/i18n/en.tsv` (`español<TAB>english`). El texto en español es la clave; los valores
  variables van como `{0}`, `{1}`: `Saldo pendiente de {0}	Balance due of {0}`.
- `Translator` + `I18nConfig`: un serializador traduce las respuestas JSON (exacto en todos los campos; plantillas
  solo en campos que redacta el servidor). Usa una copia del ObjectMapper: auditoría, base de datos y Openpay no cambian.
- `I18nCoverageTest` falla si agregas un mensaje fijo sin traducción. `I18nIT` prueba el flujo completo.
- No se traducen: el contenido que capturan hoteles y huéspedes (nombres, descripciones, políticas) ni las plantillas
  de correo, que cada hotel edita en su idioma.
