# Catálogo de permisos

Formato `MODULO:ACCION`. El catálogo es **global** (tabla `permissions`, sembrada por Flyway); cada hotel decide qué
permisos tiene cada uno de sus roles (`role_permissions`) y puede ajustar usuarios puntuales
(`user_permission_overrides`, efecto `GRANT` o `DENY`; `DENY` gana).

Permiso efectivo = (unión de permisos de sus roles) + GRANT directos − DENY directos.

- **Reservado**: solo lo puede tener el rol de sistema `ADMIN`. No se puede asignar a otro rol ni por override.
- **Sensible**: otorgarlo queda en auditoría con nivel alto y la UI pide confirmación.

| Código | Descripción | ADMIN | RECEP. | Reservado | Sensible |
|---|---|:-:|:-:|:-:|:-:|
| `DASHBOARD:VIEW` | Ver dashboard operativo | ✔ | ✔ | | |
| `AVAILABILITY:VIEW` | Consultar disponibilidad | ✔ | ✔ | | |
| `RESERVATIONS:VIEW` | Ver reservaciones | ✔ | ✔ | | |
| `RESERVATIONS:CREATE` | Crear reservaciones | ✔ | ✔ | | |
| `RESERVATIONS:UPDATE` | Modificar reservaciones (fechas, habitación, huéspedes, notas) | ✔ | ✔ | | |
| `RESERVATIONS:CONFIRM` | Confirmar reservación | ✔ | ✔ | | |
| `RESERVATIONS:CANCEL` | Cancelar conforme a la política | ✔ | ✔ | | |
| `RESERVATIONS:CHECK_IN` | Registrar check-in | ✔ | ✔ | | |
| `RESERVATIONS:CHECK_OUT` | Registrar check-out | ✔ | ✔ | | |
| `RESERVATIONS:RESEND` | Reenviar confirmación / comprobante | ✔ | ✔ | | |
| `RESERVATIONS:APPLY_DISCOUNT` | Aplicar descuento manual | ✔ | | | ✔ |
| `RESERVATIONS:OVERRIDE` | Excepciones de política (check-in con saldo, cancelación fuera de plazo sin penalización…) | ✔ | | | ✔ |
| `ROOMS:VIEW` | Ver habitaciones y calendario | ✔ | ✔ | | |
| `ROOMS:CREATE` | Crear habitaciones y tipos | ✔ | | | |
| `ROOMS:UPDATE` | Editar habitaciones, tipos, fotos, amenidades | ✔ | | | |
| `ROOMS:DELETE` | Desactivar habitaciones y tipos | ✔ | | | |
| `ROOMS:BLOCK` | Bloquear habitaciones / fechas por mantenimiento | ✔ | | | |
| `ROOMS:RELEASE_BLOCK` | Liberar bloqueos | ✔ | | | |
| `HOUSEKEEPING:VIEW` | Ver tablero de limpieza | ✔ | ✔ | | |
| `HOUSEKEEPING:UPDATE` | Marcar limpieza pendiente / completada | ✔ | ✔ | | |
| `RATES:VIEW` | Ver tarifas, temporadas, promociones | ✔ | ✔ | | |
| `RATES:MANAGE` | Crear/editar/eliminar tarifas, temporadas, promociones | ✔ | | | ✔ |
| `SERVICES:VIEW` | Ver servicios adicionales | ✔ | ✔ | | |
| `SERVICES:MANAGE` | Administrar servicios adicionales | ✔ | | | |
| `CUSTOMERS:VIEW` | Ver clientes (datos de contacto) | ✔ | ✔ | | |
| `CUSTOMERS:CREATE` | Registrar clientes | ✔ | ✔ | | |
| `CUSTOMERS:UPDATE` | Editar clientes | ✔ | ✔ | | |
| `CUSTOMERS:VIEW_SENSITIVE` | Ver identificación y datos sensibles | ✔ | | | ✔ |
| `CUSTOMERS:EXPORT` | Exportar clientes | ✔ | | | ✔ |
| `PAYMENTS:VIEW` | Ver pagos | ✔ | ✔ | | |
| `PAYMENTS:CREATE` | Registrar cobros y anticipos | ✔ | ✔ | | |
| `PAYMENTS:CREATE_LINK` | Generar enlace de pago en línea | ✔ | ✔ | | |
| `PAYMENTS:REVERSE` | Reversar un pago (corrección trazable) | ✔ | | | ✔ |
| `PAYMENTS:REFUND` | Reembolso conforme a política | ✔ | | | ✔ |
| `PAYMENTS:REFUND_EXTRAORDINARY` | Reembolso fuera de política | ✔ | | | ✔ |
| `CASH:OPERATE` | Abrir, cobrar y cerrar su propia caja | ✔ | ✔ | | |
| `CASH:VIEW_ALL` | Ver cajas de todos los usuarios | ✔ | | | |
| `CASH:ADJUST` | Movimientos autorizados (retiros, ajustes) | ✔ | | | ✔ |
| `REPORTS:OPERATIONAL` | Reportes operativos (llegadas, salidas, ocupación) | ✔ | ✔ | | |
| `REPORTS:FINANCIAL` | Reportes financieros | ✔ | | | ✔ |
| `REPORTS:EXPORT` | Exportar reportes | ✔ | | | |
| `USERS:VIEW` | Ver usuarios | ✔ | | | |
| `USERS:MANAGE` | Crear, editar, desactivar usuarios y asignar roles | ✔ | | | ✔ |
| `ROLES:MANAGE` | Roles y permisos | ✔ | | ✔ | ✔ |
| `SETTINGS:VIEW` | Ver configuración general | ✔ | | | |
| `SETTINGS:UPDATE` | Identidad, tema, contenido, horarios, parámetros | ✔ | | | |
| `POLICIES:MANAGE` | Modificar políticas | ✔ | | | ✔ |
| `NOTIFICATIONS:MANAGE` | Plantillas y destinatarios | ✔ | | | |
| `EMAIL_SETTINGS:MANAGE` | Credenciales SMTP | ✔ | | ✔ | ✔ |
| `PAYMENT_SETTINGS:MANAGE` | Métodos de pago y credenciales de pasarela | ✔ | | ✔ | ✔ |
| `AUDIT:VIEW` | Bitácora de auditoría | ✔ | | ✔ | |

## Reglas anti-escalación

1. Un usuario solo puede otorgar (a roles o usuarios) permisos que **él mismo tiene** de forma efectiva.
2. Los permisos **reservados** no se pueden asignar a roles distintos de `ADMIN` ni por override.
3. El rol `ADMIN` de sistema no puede perder permisos reservados ni `USERS:MANAGE`.
4. No se puede desactivar, quitar el rol `ADMIN` ni aplicar `DENY` de seguridad al **último administrador activo** del hotel.
5. Nadie puede modificar sus propios roles/overrides.
6. Todo cambio de rol, permiso u override se registra en `audit_logs` con valores anterior y posterior.
7. Al cambiar un rol se invalida la caché de permisos de todos sus usuarios; el siguiente request ya usa los nuevos
   permisos (sin esperar a que expire el JWT).
