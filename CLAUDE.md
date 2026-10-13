# Nexora Hotel — contexto para continuar el trabajo

Sistema hotelero **multi-hotel**: este backend (Spring Boot) y `../nexora-hotel-frontend` (React). Lo construimos
siguiendo el prompt maestro `../../promp.txt` (`C:\Users\ingar\OneDrive\Documentos\Nexora Systems\promp.txt`).
El usuario escribe en **español**; responde en español.

## Reglas que pidió el usuario (siguen vigentes)

- No modificar los proyectos de referencia **DemoPV** ni **Citas** (misma carpeta `Repositorios/`): solo se consultan.
- Sin secretos en el repositorio: `.env` está en `.gitignore`; las contraseñas viven ahí.
- Sin pagos simulados ni datos falsos en funciones de producción. Nunca decir que algo está completo si no lo está.
- Trabajar por fases y verificar cada una (pruebas + revisión en el navegador).
- **Hacer commit solo cuando el usuario lo pida.** Repos: `github.com/Alexis4152/nexora-hotel-backend` y
  `.../nexora-hotel-frontend`, rama `main` (primer push el 2026-10-13).
- Diseño muy cuidado ("perro"); al terminar algo, dejar ambos servidores corriendo y dar usuarios para validar.

## Decisiones confirmadas

- Multi-hotel como Citas multi-hospital: columna `hotel_id` + Hibernate `@TenantId`. El **SUPER_ADMIN** de Nexora
  crea los hoteles y entrega al dueño el enlace y su acceso de administrador.
- URLs de hotel: `/h/<slug>` (sin dominios propios por ahora).
- Pagos en línea: **Openpay en modo pruebas (sandbox)**. Un pago solo se confirma consultando el cargo a Openpay.
- Idioma ES | EN en toda la aplicación (ver "Idiomas").

## Stack y entorno local

- Backend: Java 17, Spring Boot 3.2.5, Hibernate 6.4, Flyway (V1–V10), PostgreSQL 18 local (BD `nexora_hotel`,
  usuario `postgres`). Puerto **8090**. Arranque: `./mvnw spring-boot:run` (lee `.env`).
- Frontend: React 18 + Vite 5 + Tailwind 3 + Vitest. Puerto **5180** (`npx vite --port 5180`; proxy `/api` → 8090).
- Pruebas backend: `./mvnw test` con Testcontainers (PostgreSQL 16). **Docker Desktop debe estar encendido**; si
  `docker info` falla, iniciarlo. Frontend: `npx vitest run`.
- `psql`: `C:/Program Files/PostgreSQL/18/bin/psql.exe` con `PGPASSWORD` (sin él se queda esperando la contraseña).
- No hay Python en la máquina: usar Node para scripts.

## Usuarios de prueba (hotel demo `gran-hotel-nexora`, perfil dev con `APP_DEMO_DATA=true`)

| Rol | Correo | Entra en |
|---|---|---|
| SUPER_ADMIN | `superadmin@nexora.mx` | http://localhost:5180/login |
| Administrador | `admin@granhotelnexora.mx` | http://localhost:5180/h/gran-hotel-nexora/login |
| Recepción | `recepcion@granhotelnexora.mx` | http://localhost:5180/h/gran-hotel-nexora/login |
| Huésped | `huesped@granhotelnexora.mx` | http://localhost:5180/h/gran-hotel-nexora/cuenta/entrar |

Contraseñas en `.env`: `APP_BOOTSTRAP_SUPER_ADMIN_PASSWORD` (super admin) y `APP_DEMO_PASSWORD` (los otros tres).

## Estado: las 10 fases del prompt están terminadas

1–8: arquitectura, base backend, seguridad, base frontend, administración hotelera, reservaciones, pagos y
operación (caja, check-in/out, no-show, limpieza), portal público y cuentas de huésped (correo verificado, ARCO).
9: dashboard + 11 reportes (cobrado, anticipos, devengado, reservado y por cobrar nunca se suman entre sí) con
exportación CSV/Excel/PDF auditada. 10: calidad y entrega. Después: selector **ES | EN** completo y **campanita de
notificaciones en vivo** (ver abajo).

Última verificación: backend **171 pruebas**, frontend **50 pruebas**, todas en verde; build final y las dos imágenes
Docker construidas y probadas.

Documentación en `docs/`: 01 arquitectura, 02 modelo de datos, 03 permisos, 04 reportes, 05 calidad y despliegue
(guía paso a paso para Coolify con lista de verificación).

## Idiomas (ES | EN)

Estilo gettext: **el texto en español es la clave**.
- Frontend: `tx('Texto')` / `tx('Hola, {0}', nombre)` en `src/i18n/index.js`; diccionario `src/i18n/en.json`
  (se descarga solo en inglés). Cambiar de idioma recarga la página. Fechas/moneda con `LOCALE`.
- Backend: `i18n/Translator` + `i18n/I18nConfig` (serializador solo en el convertidor HTTP); diccionario
  `src/main/resources/i18n/en.tsv` (`español<TAB>english`, valores como `{0}`). La app manda `Accept-Language`;
  **sin encabezado responde en español**.
- Pruebas que fallan si falta una traducción: `src/test/i18n.test.js` (front) e `I18nCoverageTest` (back).
  **Todo texto nuevo visible debe agregarse a ambos diccionarios.**
- No se traduce lo que captura el hotel (nombres, descripciones, políticas) ni las plantillas de correo.

## Notificaciones del panel (campanita)

- Backend: tabla `staff_notifications` (V10), `notification/StaffNotificationType` (tipo → categoría, permiso de quien la
  recibe, severidad), `StaffNotifier` (reparte por permiso, excluye a quien hizo la acción, guarda en la transacción y
  empuja después del commit), `NotificationStream` (SSE en memoria, una instancia), `OperationsAlertService` (llegadas,
  salidas atrasadas, posibles no-shows; cada 5 min desde `ReservationJobs`). API `/api/notifications` (fuera de
  `/api/admin` a propósito: no exige permiso concreto, solo `HOTEL_STAFF`).
- Se guarda tipo + `params`, no texto: el frontend redacta en `components/notifications.jsx` (`describe`). Un tipo nuevo
  = enum + `describe` + traducciones.
- Frontend: `context/NotificationsContext.jsx` abre el SSE con `fetch` (EventSource no manda `Authorization`), se
  reconecta solo; `components/NotificationBell.jsx`; página `/panel/notificaciones`. Enlaces: `?r=<id>` en
  Reservaciones abre la reservación, `?tab=solicitudes` en Clientes.

## Trampas conocidas

- La JVM debe correr en **UTC** (bloque estático en `NexoraHotelApplication`, `argLine` de surefire,
  `jvmArguments` de spring-boot y `JAVA_OPTS` del Dockerfile). Si no, `hibernate.jdbc.time_zone=UTC` corre las
  columnas `TIME`: el check-in de 15:00 se leía como 09:00.
- Detener la tarea de `mvnw spring-boot:run` deja vivo el proceso java en :8090: matar el PID que escucha en 8090
  antes de volver a arrancar (si no, el backend nuevo falla y sigue respondiendo el viejo).
- En Git Bash, las rutas `/h/...` pasadas a scripts de Node se convierten en rutas de Windows: usar
  `MSYS_NO_PATHCONV=1`.
- Verificación responsiva: Edge headless + protocolo DevTools (Node 24 trae WebSocket) emulando 360/390 px; las
  capturas con `--screenshot` sin emulación móvil recortan y engañan.
- Menús desplegables: usar `ActionMenu` (portal) de `components/ui`; dentro de tarjetas con `overflow-hidden` un menú
  absoluto se recorta.
- Respuestas JSON omiten los campos nulos (en pruebas usar `isMissingNode()`).

## Pendientes y mejoras conocidas

- `react-router` 6.x tiene 2 avisos moderados de `npm audit`; la corrección exige migrar a la v7 (cambios
  incompatibles). Mitigado con `stripHotelPrefix` endurecido.
- El límite de solicitudes vive en memoria: desplegar **una sola instancia** del backend.
- Despliegue real en Coolify pendiente (seguir `docs/05-CALIDAD-Y-DESPLIEGUE.md`).
