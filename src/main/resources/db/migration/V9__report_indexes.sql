-- =====================================================================================
-- Nexora Hotel — V9: índices para reportes y dashboard (Fase 9/10).
-- Cada reporte filtra por hotel y por la fecha que le corresponde (creación, cancelación, pago completado…).
-- =====================================================================================
CREATE INDEX idx_reservations_hotel_created   ON reservations (hotel_id, created_at);
CREATE INDEX idx_reservations_hotel_cancelled ON reservations (hotel_id, cancelled_at) WHERE cancelled_at IS NOT NULL;
CREATE INDEX idx_reservations_hotel_no_show   ON reservations (hotel_id, no_show_at) WHERE no_show_at IS NOT NULL;
CREATE INDEX idx_payments_hotel_completed     ON payments (hotel_id, completed_at) WHERE status = 'COMPLETED';
CREATE INDEX idx_cash_movements_hotel_created ON cash_movements (hotel_id, created_at);
CREATE INDEX idx_cash_sessions_hotel_closed   ON cash_sessions (hotel_id, closed_at) WHERE status = 'CLOSED';
CREATE INDEX idx_reservation_charges_hotel    ON reservation_charges (hotel_id, created_at) WHERE voided_at IS NULL;
CREATE INDEX idx_reservation_rooms_type       ON reservation_rooms (reservation_id, room_type_id);
