# 04 · Reportes y dashboard

Todos los reportes salen de datos reales del hotel actual: no hay cifras de ejemplo ni estimadas. Las fechas se
interpretan en la **zona horaria del hotel** y cada reporte declara sobre qué fecha se filtra (su *base*).

## Qué representa cada cifra

El prompt pide no mezclar conceptos. El sistema usa estos cinco, siempre con su nombre y nunca sumados entre sí:

| Concepto | Definición | Dónde aparece |
|---|---|---|
| **Cobrado** | Pagos completados menos reembolsos y reversos, por la fecha en que se completaron. Es dinero recibido. | Dashboard, Ingresos, Pagos |
| **Anticipo** | Parte de lo cobrado que se pagó **antes** del día de llegada de su reservación. | Dashboard, Ingresos |
| **Devengado** | Valor de las noches ocupadas en el periodo (total de la estancia repartido entre sus noches, con impuestos), más cargos extra no anulados y penalizaciones aplicadas. **No es dinero cobrado.** | Ingresos |
| **Reservado** | Valor total de reservaciones vigentes o realizadas, se haya cobrado o no. | Reservaciones, Clientes |
| **Por cobrar** | Lo adeudado menos lo pagado en cuentas comprometidas (confirmadas, en estancia, finalizadas, canceladas o no show con penalización). Las pendientes de confirmar no cuentan: su importe aún no es una deuda. | Dashboard, Cuentas por cobrar |

Un anticipo cobrado hoy para una estancia del mes próximo aparece hoy en *Cobrado* y el mes próximo en *Devengado*.

## Catálogo

| Reporte | Permiso | Base de fecha | Filtros |
|---|---|---|---|
| Reservaciones | `REPORTS:OPERATIONAL` | Creación o llegada (a elegir) | estado, canal, tipo de habitación |
| Ocupación | `REPORTS:OPERATIONAL` | Noche de estancia | tipo de habitación |
| Habitaciones más reservadas | `REPORTS:OPERATIONAL` | Noches dentro del periodo | tipo de habitación |
| Cancelaciones | `REPORTS:OPERATIONAL` | Fecha de cancelación / no show | canal, tipo de habitación |
| Clientes recurrentes | `REPORTS:OPERATIONAL` | Llegada en el periodo (historial completo) | — |
| Reservaciones pendientes | `REPORTS:OPERATIONAL` | Situación actual | canal |
| Ingresos por periodo | `REPORTS:FINANCIAL` | Pago (cobrado) / noche (devengado) | — |
| Pagos por método | `REPORTS:FINANCIAL` | Fecha en que se completó | método, tipo, estado |
| Reembolsos | `REPORTS:FINANCIAL` | Fecha en que se completó | método |
| Cuentas por cobrar | `REPORTS:FINANCIAL` | Situación actual | estado |
| Movimientos de caja | `REPORTS:FINANCIAL` | Fecha del movimiento | método |

Sin `REPORTS:FINANCIAL`, los reportes operativos ocultan las columnas e indicadores de dinero, y el dashboard no
envía la sección *Dinero* (el servidor no la incluye; no es solo un ocultamiento visual).

## Ocupación

- **Vendida**: habitación-noche con reservación confirmada, en estancia o finalizada. Pendientes y canceladas no.
- **Disponible**: habitaciones activas menos las bloqueadas por mantenimiento esa noche.
- Se usa el inventario **actual**: una habitación dada de baja deja de contar también en periodos pasados.

## Exportación

`GET /api/admin/reports/{tipo}/export?format=csv|xlsx|pdf` con los mismos filtros que la consulta. Exige
`REPORTS:EXPORT` **y** el permiso del reporte; cada exportación queda en la bitácora (`REPORT_EXPORTED`) y tiene
límite de 30 por minuto.

- **CSV**: UTF-8 con BOM (Excel respeta acentos), importes sin formato para poder sumarlos.
- **Excel**: hoja *Resumen* (indicadores con su explicación y notas) y hoja *Detalle* con formato de moneda y fecha.
- **PDF**: horizontal, indicadores arriba y tabla con encabezado repetido en cada página.
- Las celdas de texto que empiezan con `= + - @` se prefijan con `'` para que una hoja de cálculo no las ejecute
  como fórmula (un huésped podría escribir `=HYPERLINK(...)` en su nombre o en un motivo).

La pantalla muestra hasta 500 renglones; la exportación hasta 20 000. El periodo máximo es de 366 días.

## Endpoints

| Método | Ruta | Permiso |
|---|---|---|
| GET | `/api/admin/dashboard` | `DASHBOARD:VIEW` (cifras de dinero solo con `REPORTS:FINANCIAL`) |
| GET | `/api/admin/reports` | Catálogo filtrado por permisos |
| GET | `/api/admin/reports/{tipo}` | Operativo o financiero según el tipo |
| GET | `/api/admin/reports/{tipo}/export` | `REPORTS:EXPORT` + el del tipo |

Pruebas: `ReportIT` (cifras con un escenario conocido, aislamiento entre hoteles, permisos, exportaciones,
neutralización de fórmulas y validación de filtros).
