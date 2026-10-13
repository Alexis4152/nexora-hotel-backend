# 05 · Calidad y despliegue

Resultado de la Fase 10 y guía para poner Nexora Hotel en producción con Coolify.

## 1. Pruebas

| Proyecto | Comando | Qué cubre |
|---|---|---|
| Backend | `./mvnw test` (Docker encendido) | Unitarias (precios, máquina de estados, validaciones, limitador, arranque en producción) e integración contra **PostgreSQL real** en Testcontainers: seguridad, aislamiento entre hoteles, catálogo, reservaciones concurrentes, pagos con simulador HTTP de Openpay, operación de estancia, cuentas de huésped, reportes y cobertura de permisos. |
| Frontend | `npm test` | Validaciones, permisos y render de cada pantalla con la API simulada (detecta pantallas que truenan). |

Pruebas que protegen reglas críticas:

- `PermissionCoverageIT` recorre **todos** los endpoints registrados (no una lista escrita a mano): cada uno de
  `/api/admin/**` y `/api/platform/**` declara un permiso concreto, y un usuario sin permisos recibe 403 en todos.
- `TenantIsolationIT`: un hotel nunca lee ni modifica datos de otro.
- `ReservationIT`: dos huéspedes reservando la última habitación a la vez → solo uno gana.
- `PaymentsIT`: un pago en línea solo se confirma consultando a la pasarela; webhooks repetidos no duplican.
- `ReportIT`: cobrado, anticipos, devengado y por cobrar con un escenario de cifras conocidas.

## 2. Revisión de seguridad

| Área | Estado |
|---|---|
| Autenticación | JWT de vida corta en memoria + refresh rotado en cookie httpOnly por hotel; bloqueo por intentos; recuperación con token de un solo uso. |
| Autorización | Permisos dinámicos validados en el servidor en **cada** endpoint (prueba automática). El frontend solo oculta. |
| Multi-hotel | `@TenantId` de Hibernate + `TenantGuard`; las consultas JDBC filtran `hotel_id` explícitamente. |
| SQL | Sin concatenación de valores del usuario: todo va como parámetro; los filtros de reportes solo aceptan códigos conocidos. |
| Exportaciones | Neutralización de fórmulas en CSV/Excel; auditadas; límite por minuto. |
| Redirecciones | Después del login solo se aceptan rutas internas (`/` seguido de algo que no sea `/` ni `\`). |
| Secretos | Solo en variables de entorno; `.env` ignorado por git; llaves de Openpay y SMTP cifradas en la base. El perfil `prod` **no arranca** con secretos débiles, repetidos, almacenamiento relativo, cookie insegura o CORS local. |
| Errores | Producción no expone stacktraces ni mensajes internos; Swagger apagado; actuator solo `health`. |
| Cabeceras | `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy` en la API y en el nginx del frontend (verificadas con la imagen construida). |
| Pagos | Tablas financieras de solo inserción protegidas con triggers; idempotencia por `Idempotency-Key`; webhook con autenticación básica en tiempo constante. |

**Dependencias.** `npm audit` reporta 2 avisos moderados en `react-router` 6.x:

- Uno aplica solo a SSR, que este proyecto no usa.
- El otro es de redirección abierta con `\`. Aquí no es explotable porque los destinos de navegación los fija la app; además se endureció `stripHotelPrefix` y se agregó una prueba.

La corrección oficial exige migrar a react-router 7, que trae cambios incompatibles. Queda recomendada como mantenimiento.

**Corregido en esta fase:**

1. **Horas del hotel corridas por la zona de la JVM.** Con `hibernate.jdbc.time_zone=UTC` y la JVM en otra zona, las columnas `TIME` se leían desplazadas (15:00 aparecía como 09:00 en UTC-6). Ahora la JVM corre siempre en UTC (`main`, pruebas, `spring-boot:run` y Docker). Prueba de regresión en `ReservationIT`.
2. **Reserva del mismo día rechazada con "al menos 0 horas de anticipación".** Sin anticipación mínima ya no se compara contra la hora de check-in.
3. **Cabeceras de seguridad ausentes en el frontend.** nginx no hereda los `add_header` del server cuando un `location` define los suyos, así que `X-Frame-Options`, `nosniff` y `Referrer-Policy` no se enviaban. Ahora van en cada `location`, y `server_tokens off` oculta la versión de nginx.

**Imágenes Docker verificadas.** Ambas se construyeron:

- El frontend sirve la app y el respaldo SPA con sus cabeceras.
- El backend en perfil `prod` se negó a arrancar con un secreto débil, con el mensaje claro de la variable que falla.

## 3. Optimización

- Frontend con carga diferida por pantalla. El bundle inicial bajó de 702 kB a 269 kB (de 196 a 90 kB con gzip), así que la portada pública no descarga el panel.
- Migración `V9__report_indexes.sql`: índices por hotel y fecha para reportes (creación, cancelación, no show, pagos completados, movimientos de caja, cargos).
- Los reportes agregan en una sola pasada por periodo (máximo 366 días); la pantalla limita a 500 renglones.

## 4. Verificación responsiva

Se midió el desbordamiento horizontal con Edge headless emulando un celular (390 y 360 px de ancho) en:

- 14 pantallas del panel (dashboard, reservaciones, habitaciones, clientes, caja, pagos, tarifas, servicios, reportes, configuración, usuarios, roles y auditoría),
- el portal público (portada, reservar, políticas, mi reservación, registro),
- el login y la portada de la plataforma.

Resultado: ninguna desborda. Se corrigieron dos casos:

- La cuadrícula inferior del dashboard se ensanchaba 18 px.
- Los campos de fecha de reportes se salían de su tarjeta.

## 5. Archivos persistentes

Las imágenes se guardan en `APP_STORAGE_DIR`, fuera del contenedor, y la base solo registra su ruta. Verificado:

1. Se subió una imagen por la API.
2. Se reinició el backend.
3. La misma URL respondió 200 con el mismo contenido.

En producción, `prod` exige que `APP_STORAGE_DIR` sea una ruta absoluta y escribible. El `Dockerfile` declara el volumen `/data/storage`.

## 6. Despliegue en Coolify

### Recursos

1. **PostgreSQL 16** (servicio de Coolify) con respaldo automático diario. El esquema lo crea Flyway al arrancar el
   backend; `db/init.sql` solo hace falta si el usuario de la aplicación no puede crear la extensión `btree_gist`.
2. **Backend**: aplicación desde el repositorio `nexora-hotel-backend`, build con su `Dockerfile`, puerto 8090.
3. **Frontend**: aplicación desde `nexora-hotel-frontend`, build con su `Dockerfile`, puerto 80.

### Backend: variables

| Variable | Valor |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | Los del PostgreSQL de Coolify |
| `APP_JWT_SECRET`, `APP_ENCRYPTION_KEY` | Dos valores distintos de `openssl rand -base64 48`. **No cambies `APP_ENCRYPTION_KEY` después**: con ella se cifran las llaves de Openpay y SMTP guardadas. |
| `APP_FRONTEND_URL` | `https://hotel.tu-dominio.mx` |
| `APP_PUBLIC_URL` | `https://api.tu-dominio.mx` (con él se arman las URLs de imágenes y del webhook) |
| `APP_CORS_ALLOWED_ORIGINS` | `https://hotel.tu-dominio.mx` |
| `APP_COOKIE_SECURE` | `true` |
| `APP_COOKIE_SAMESITE` | `None` si frontend y API están en dominios distintos; `Lax` si comparten dominio |
| `APP_STORAGE_DIR` | `/data/storage` |
| `APP_BOOTSTRAP_SUPER_ADMIN_EMAIL` / `_PASSWORD` | Solo para el primer arranque; bórralas después |
| `MAIL_*` | SMTP de plataforma (recuperación de contraseñas) |

**Storages:** en Coolify > *Persistent Storage* agrega un **Volume Mount** (no *Bind Mount*) con destino `/data/storage`.
Sin él, las fotos se pierden en cada redeploy. Debe ser un volumen de Docker: hereda el dueño de la carpeta de la imagen
(usuario `app`, sin privilegios); una carpeta del servidor montada directo quedaría de `root` y el backend no arrancaría
(`prod` exige que la ruta sea escribible).

**Health check:** `/actuator/health` (ya definido en el `Dockerfile`).

**Instancias:** despliega **una sola**. El límite de solicitudes y las conexiones en vivo de la campanita viven en memoria. La cola de correos ya tolera varias instancias (`SKIP LOCKED`), pero el limitador no.

### Frontend: build

| Argumento | Valor |
|---|---|
| `VITE_API_URL` | `https://api.tu-dominio.mx/api`. Es un **Build Variable** (se incrusta al compilar): si cambia, hay que volver a desplegar. Obligatorio en Coolify, donde frontend y API son aplicaciones con dominios distintos (el Nginx del frontend no reenvía `/api`). |

### Lista de verificación al desplegar

- [ ] `https://api.tu-dominio.mx/actuator/health` responde `{"status":"UP"}`.
- [ ] El log muestra las migraciones de Flyway hasta la V10 sin errores.
- [ ] Entrar con el SUPER_ADMIN, crear el hotel y borrar las variables `APP_BOOTSTRAP_*`.
- [ ] Iniciar sesión como administrador del hotel en `https://hotel.tu-dominio.mx/h/<slug>/login`.
- [ ] Subir una foto, hacer redeploy del backend y comprobar que la foto sigue visible.
- [ ] Abrir la campanita del panel: debe decir **En vivo** (si dice *Reconectando…*, el proxy está cortando la conexión).
- [ ] Configuración → Correo: guardar el SMTP del hotel y enviar una prueba.
- [ ] Configuración → Pagos: llaves de Openpay en modo pruebas y **Probar conexión**. Registrar el webhook (URL que muestra la pantalla) con las credenciales generadas.
- [ ] Hacer una reservación desde el portal con una tarjeta de prueba de Openpay y verificar que se confirma.
- [ ] Revisar Configuración → horarios: check-in y check-out se ven con la hora que se capturó.
- [ ] Pasar Openpay a producción solo después de lo anterior (llaves de producción y *Modo pruebas* apagado).

### Respaldo y recuperación

- Base de datos: respaldo de Coolify; prueba restaurarlo en otra base al menos una vez.
- Imágenes: incluye el volumen `/data/storage` en el respaldo (la base solo guarda rutas).
