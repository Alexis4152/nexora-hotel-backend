-- =====================================================================================
-- Nexora Hotel — V2: catálogo global de permisos (docs/03-PERMISOS.md).
-- Columnas: module, action, description, reserved, sensitive, default_receptionist, sort_order
-- Agregar un permiso nuevo = nueva migración con INSERT; nunca editar ésta.
-- =====================================================================================
INSERT INTO permissions (code, module, action, description, reserved, sensitive, default_receptionist, sort_order)
SELECT module || ':' || action, module, action, description, reserved, sensitive, default_receptionist, sort_order
FROM (VALUES
    ('DASHBOARD',        'VIEW',                 'Ver dashboard operativo',                                        FALSE, FALSE, TRUE,   10),
    ('AVAILABILITY',     'VIEW',                 'Consultar disponibilidad',                                       FALSE, FALSE, TRUE,   20),

    ('RESERVATIONS',     'VIEW',                 'Ver reservaciones',                                              FALSE, FALSE, TRUE,  100),
    ('RESERVATIONS',     'CREATE',               'Crear reservaciones',                                            FALSE, FALSE, TRUE,  101),
    ('RESERVATIONS',     'UPDATE',               'Modificar reservaciones',                                        FALSE, FALSE, TRUE,  102),
    ('RESERVATIONS',     'CONFIRM',              'Confirmar reservaciones',                                        FALSE, FALSE, TRUE,  103),
    ('RESERVATIONS',     'CANCEL',               'Cancelar reservaciones conforme a la política',                  FALSE, FALSE, TRUE,  104),
    ('RESERVATIONS',     'CHECK_IN',             'Registrar check-in',                                             FALSE, FALSE, TRUE,  105),
    ('RESERVATIONS',     'CHECK_OUT',            'Registrar check-out',                                            FALSE, FALSE, TRUE,  106),
    ('RESERVATIONS',     'RESEND',               'Reenviar confirmaciones y comprobantes',                         FALSE, FALSE, TRUE,  107),
    ('RESERVATIONS',     'APPLY_DISCOUNT',       'Aplicar descuentos manuales',                                    FALSE, TRUE,  FALSE, 108),
    ('RESERVATIONS',     'OVERRIDE',             'Autorizar excepciones a las políticas',                          FALSE, TRUE,  FALSE, 109),

    ('ROOMS',            'VIEW',                 'Ver habitaciones y calendario de ocupación',                     FALSE, FALSE, TRUE,  200),
    ('ROOMS',            'CREATE',               'Crear habitaciones y tipos de habitación',                       FALSE, FALSE, FALSE, 201),
    ('ROOMS',            'UPDATE',               'Editar habitaciones, tipos, fotografías y amenidades',           FALSE, FALSE, FALSE, 202),
    ('ROOMS',            'DELETE',               'Desactivar habitaciones y tipos',                                FALSE, FALSE, FALSE, 203),
    ('ROOMS',            'BLOCK',                'Bloquear habitaciones por mantenimiento',                        FALSE, FALSE, FALSE, 204),
    ('ROOMS',            'RELEASE_BLOCK',        'Liberar bloqueos de mantenimiento',                              FALSE, FALSE, FALSE, 205),
    ('HOUSEKEEPING',     'VIEW',                 'Ver tablero de limpieza',                                        FALSE, FALSE, TRUE,  210),
    ('HOUSEKEEPING',     'UPDATE',               'Marcar limpieza pendiente o completada',                         FALSE, FALSE, TRUE,  211),

    ('RATES',            'VIEW',                 'Ver tarifas, temporadas y promociones',                          FALSE, FALSE, TRUE,  300),
    ('RATES',            'MANAGE',               'Administrar tarifas, temporadas y promociones',                  FALSE, TRUE,  FALSE, 301),
    ('SERVICES',         'VIEW',                 'Ver servicios adicionales',                                      FALSE, FALSE, TRUE,  310),
    ('SERVICES',         'MANAGE',               'Administrar servicios adicionales',                              FALSE, FALSE, FALSE, 311),

    ('CUSTOMERS',        'VIEW',                 'Ver clientes y sus datos de contacto',                           FALSE, FALSE, TRUE,  400),
    ('CUSTOMERS',        'CREATE',               'Registrar clientes',                                             FALSE, FALSE, TRUE,  401),
    ('CUSTOMERS',        'UPDATE',               'Editar clientes',                                                FALSE, FALSE, TRUE,  402),
    ('CUSTOMERS',        'VIEW_SENSITIVE',       'Ver identificaciones y datos sensibles',                         FALSE, TRUE,  FALSE, 403),
    ('CUSTOMERS',        'EXPORT',               'Exportar clientes',                                              FALSE, TRUE,  FALSE, 404),

    ('PAYMENTS',         'VIEW',                 'Ver pagos',                                                      FALSE, FALSE, TRUE,  500),
    ('PAYMENTS',         'CREATE',               'Registrar cobros y anticipos',                                   FALSE, FALSE, TRUE,  501),
    ('PAYMENTS',         'CREATE_LINK',          'Generar enlaces de pago en línea',                               FALSE, FALSE, TRUE,  502),
    ('PAYMENTS',         'REVERSE',              'Reversar pagos con motivo',                                      FALSE, TRUE,  FALSE, 503),
    ('PAYMENTS',         'REFUND',               'Reembolsar conforme a la política',                              FALSE, TRUE,  FALSE, 504),
    ('PAYMENTS',         'REFUND_EXTRAORDINARY', 'Autorizar reembolsos fuera de política',                         FALSE, TRUE,  FALSE, 505),
    ('CASH',             'OPERATE',              'Abrir, operar y cerrar su propia caja',                          FALSE, FALSE, TRUE,  510),
    ('CASH',             'VIEW_ALL',             'Ver las cajas de todos los usuarios',                            FALSE, FALSE, FALSE, 511),
    ('CASH',             'ADJUST',               'Registrar retiros y ajustes de caja',                            FALSE, TRUE,  FALSE, 512),

    ('REPORTS',          'OPERATIONAL',          'Ver reportes operativos',                                        FALSE, FALSE, TRUE,  600),
    ('REPORTS',          'FINANCIAL',            'Ver reportes financieros',                                       FALSE, TRUE,  FALSE, 601),
    ('REPORTS',          'EXPORT',               'Exportar reportes',                                              FALSE, FALSE, FALSE, 602),

    ('USERS',            'VIEW',                 'Ver usuarios',                                                   FALSE, FALSE, FALSE, 700),
    ('USERS',            'MANAGE',               'Crear, editar y desactivar usuarios y asignar roles',            FALSE, TRUE,  FALSE, 701),
    ('ROLES',            'MANAGE',               'Administrar roles y permisos',                                   TRUE,  TRUE,  FALSE, 702),
    ('AUDIT',            'VIEW',                 'Consultar la bitácora de auditoría',                             TRUE,  FALSE, FALSE, 703),

    ('SETTINGS',         'VIEW',                 'Ver la configuración general',                                   FALSE, FALSE, FALSE, 800),
    ('SETTINGS',         'UPDATE',               'Modificar identidad, tema, contenido, horarios y parámetros',    FALSE, FALSE, FALSE, 801),
    ('POLICIES',         'MANAGE',               'Modificar políticas de reservación',                             FALSE, TRUE,  FALSE, 802),
    ('NOTIFICATIONS',    'MANAGE',               'Administrar plantillas y destinatarios de notificaciones',       FALSE, FALSE, FALSE, 803),
    ('EMAIL_SETTINGS',   'MANAGE',               'Administrar credenciales de correo',                             TRUE,  TRUE,  FALSE, 804),
    ('PAYMENT_SETTINGS', 'MANAGE',               'Administrar métodos de pago y credenciales de pasarela',         TRUE,  TRUE,  FALSE, 805)
) AS p (module, action, description, reserved, sensitive, default_receptionist, sort_order);

-- Rol de plataforma (único, sin hotel). No lleva permisos de hotel: opera en /api/platform/**.
INSERT INTO roles (hotel_id, code, name, description, system)
VALUES (NULL, 'SUPER_ADMIN', 'Super administrador', 'Nexora: alta y administración de hoteles', TRUE);
