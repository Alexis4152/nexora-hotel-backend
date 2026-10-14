-- Rol de sistema "Camarista" para los hoteles que ya existen (los nuevos lo reciben al crearse, igual que
-- Administrador y Recepcionista). Solo limpieza: ve el tablero y marca habitaciones listas o por limpiar.
-- Si un hotel ya había creado a mano un rol con ese mismo código, se respeta y no se toca.
INSERT INTO roles (hotel_id, code, name, description, system)
SELECT h.id, 'HOUSEKEEPER', 'Camarista', 'Limpieza de habitaciones', TRUE
FROM hotels h
WHERE NOT EXISTS (SELECT 1 FROM roles r WHERE r.hotel_id = h.id AND r.code = 'HOUSEKEEPER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('HOUSEKEEPING:VIEW', 'HOUSEKEEPING:UPDATE')
WHERE r.code = 'HOUSEKEEPER' AND r.system
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
